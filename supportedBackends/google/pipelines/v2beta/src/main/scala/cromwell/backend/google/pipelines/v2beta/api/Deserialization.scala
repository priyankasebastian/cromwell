package cromwell.backend.google.pipelines.v2beta.api

import java.lang.reflect.ParameterizedType
import java.util.{ArrayList => JArrayList, Map => JMap}

import cats.instances.list._
import cats.syntax.traverse._
import cats.syntax.validated._
import com.google.api.client.json.GenericJson
import com.google.api.services.lifesciences.v2beta.model._
import common.validation.ErrorOr._
import common.validation.Validation._
import mouse.all._

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * This bundles up some code to work around the fact that Operation is not deserialized
  * completely from the JSON HTTP response.
  * For instance, the metadata field is a Map[String, Object] even though it represents "Metadata"
  * for which there's an existing class.
  * This class provides implicit functions to deserialize those map to their proper type.
  */
private [api] object Deserialization {

  implicit class OperationDeserialization(val operation: Operation) extends AnyVal {
    /**
      * Deserializes the events to com.google.api.services.genomics.v2beta.model.Event
      *
      * There could also be entries of `com.google.api.services.lifesciences.v2beta.model.DelayedEvent`
      * They are effectively upcast to regular `Event` even though they're unrelated types
      */
    def events: ErrorOr[List[Event]] = {
      val eventsErrorOrOption = for {
        eventsMap <- metadata.get("events")
        eventsErrorOr <- Option(eventsMap
          .asInstanceOf[JArrayList[JMap[String, Object]]]
          .asScala
          .toList
          .traverse[ErrorOr, Event](deserializeTo[Event](_).toErrorOr)
        )
      } yield eventsErrorOr
      eventsErrorOrOption.getOrElse(Nil.validNel)
    }

    /**
      * Deserializes the pipeline to com.google.api.services.genomics.v2beta.model.Pipeline
      */
    def pipeline: Option[Try[Pipeline]] = {
      metadata
        .get("pipeline")
        .map(_.asInstanceOf[JMap[String, Object]] |> deserializeTo[Pipeline])
    }

    // If there's a WorkerAssignedEvent it means a VM was created - which we consider as the job started
    // Note that the VM might still be booting
    def hasStarted = events.toOption.exists(_.exists(_.getWorkerAssigned != null))

    def metadata: Map[String, AnyRef] = {
      val metadataOption = for {
        operationValue <- Option(operation)
        metadataJava <- Option(operationValue.getMetadata)
      } yield metadataJava.asScala.toMap
      metadataOption.getOrElse(Map.empty)
    }
  }

  /**
    * Deserializes a java.util.Map[String, Object] to an instance of T
    */
  private [api] def deserializeTo[T <: GenericJson](attributes: JMap[String, Object])(implicit tag: ClassTag[T]): Try[T] = Try {
    // Create a new instance, because it's a GenericJson there's always a 0-arg constructor
    val newT = tag.runtimeClass.asInstanceOf[Class[T]].getConstructor().newInstance()

    // Optionally returns the field with the given name
    def field(name: String) = Option(newT.getClassInfo.getField(name))

    def handleMap(key: String, value: Object) = {
      (field(key), value) match {
        // If the serialized value is a list, we need to check if its elements need to be deserialized
        case (Some(f), list: java.util.List[java.util.Map[String, Object]] @unchecked) =>
          // Try to get the generic type of the declared field (the field should have a list type since the value is a list)
          Try(f.getGenericType.asInstanceOf[ParameterizedType].getActualTypeArguments.toList.head.asInstanceOf[Class[_]]) match {
            // If we can get it and its a GenericJson, it means we need to deserialize the elements to their proper type
            case Success(genericListType) if classOf[GenericJson].isAssignableFrom(genericListType) =>
              // The get throws at the first error and hence doesn't aggregate the errors but it seems
              // overly complicated to aggregate them for not much gain here
              val deserialized = list.asScala.map(deserializeTo(_)(ClassTag[GenericJson](genericListType)).get).asJava
              newT.set(key, deserialized)
            // If it's not a GenericJson it means no further deserialization is needed, just set it as is
            case Success(_) => newT.set(key, value)
            // If the value is a list but the type has no generic type something is off, better not do anything and leave the field null
            case Failure(_) =>
          }

        // If the value can be assigned directly to the field, just do that
        case (Some(f), _) if f.getType.isAssignableFrom(value.getClass) => newT.set(key, value)

        // If it can't be assigned and the value is a map, it is very likely that the field "key" of T is of some type U
        // but has been deserialized to a Map[String, Object]. In this case we retrieve the type U from the field and recurse
        // to deserialize properly
        case (Some(f), map: java.util.Map[String, Object] @unchecked) if classOf[GenericJson].isAssignableFrom(f.getType) =>
          // This whole function is wrapped in a try so just .get to throw
          val deserializedInnerAttribute = deserializeTo(map)(ClassTag[GenericJson](f.getType)).get
          newT.set(key, deserializedInnerAttribute)

        // The set method trips up on some type mismatch between number types, this helps it
        case (Some(f), number: Number) if f.getType == classOf[java.lang.Integer] => newT.set(key, number.intValue())
        case (Some(f), number: Number) if f.getType == classOf[java.lang.Double] => newT.set(key, number.doubleValue())
        case (Some(f), number: Number) if f.getType == classOf[java.lang.Float] => newT.set(key, number.floatValue())
        case (Some(f), number: Number) if f.getType == classOf[java.lang.Long] => newT.set(key, number.longValue())
        // If either the key is not an attribute of T, or we can't assign it - just skip it
        // The only effect is that an attribute might not be populated and would be null.
        // We would only notice if we do look at this attribute though, which we only do with the purpose of populating metadata
        // Worst case scenario is thus "a metadata value is null" which seems better over failing the whole deserialization
        // and losing properly deserialized attributes
        case _ =>
      }
    }

    // Go over the map entries and use the "set" method of GenericJson to set the attributes.
    Option(attributes).map(_.asScala).getOrElse(Map.empty).foreach((handleMap _).tupled)
    newT
  }
}
