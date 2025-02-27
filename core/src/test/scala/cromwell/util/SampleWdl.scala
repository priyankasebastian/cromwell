package cromwell.util

import java.util.UUID

import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.{WorkflowOptions, WorkflowSourceFilesCollection, WorkflowSourceFilesWithDependenciesZip, WorkflowSourceFilesWithoutImports}
import spray.json._
import wom.core.{ExecutableInputMap, WorkflowJson, WorkflowSource}
import wom.values._

import scala.language.postfixOps

case class WorkflowImport(name: String, content: String)

trait SampleWdl extends TestFileUtil {
  def workflowSource(runtime: String = ""): WorkflowSource

  def imports: Option[Set[WorkflowImport]] = None

  def importsZip: Option[Array[Byte]] = imports map { imports_ =>
    val stagingDir = DefaultPathBuilder.createTempDirectory("")
    imports_ foreach { import_ =>
      stagingDir.resolve(import_.name).write(import_.content)
    }
    stagingDir.zip().byteArray
  }

  def asWorkflowSources(runtime: String = "",
                        workflowOptions: String = "{}",
                        labels: String = "{}",
                        workflowType: Option[String] = Option("WDL"),
                        workflowTypeVersion: Option[String] = None,
                        workflowOnHold: Boolean = false): WorkflowSourceFilesCollection = {
    importsZip match {
      case Some(zip) =>
        WorkflowSourceFilesWithDependenciesZip(
          workflowSource = Option(workflowSource(runtime)),
          workflowUrl = None,
          workflowRoot = None,
          inputsJson = workflowJson,
          workflowOptions = WorkflowOptions.fromJsonString(workflowOptions).get,
          labelsJson = labels,
          workflowType = workflowType,
          workflowTypeVersion = workflowTypeVersion,
          warnings = Vector.empty,
          workflowOnHold = workflowOnHold,
          importsZip = zip,
          requestedWorkflowId = None)
      case None =>
        WorkflowSourceFilesWithoutImports(
          workflowSource = Option(workflowSource(runtime)),
          workflowUrl = None,
          workflowRoot = None,
          inputsJson = workflowJson,
          workflowOptions = WorkflowOptions.fromJsonString(workflowOptions).get,
          labelsJson = labels,
          workflowType = workflowType,
          workflowTypeVersion = workflowTypeVersion,
          warnings = Vector.empty,
          workflowOnHold = workflowOnHold,
          requestedWorkflowId = None)
    }
  }

  val rawInputs: ExecutableInputMap

  def name = getClass.getSimpleName.stripSuffix("$")

  def createFileArray(base: Path): Unit = {
    createFile("f1", base, "line1\nline2\n")
    createFile("f2", base, "line3\nline4\n")
    createFile("f3", base, "line5\n")
    ()
  }

  def cleanupFileArray(base: Path) = {
    deleteFile(base.resolve("f1"))
    deleteFile(base.resolve("f2"))
    deleteFile(base.resolve("f3"))
  }

  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any) = x match {
      case n: Int => JsNumber(n)
      case s: String => JsString(s)
      case b: Boolean => if(b) JsTrue else JsFalse
      case s: Seq[Any] => JsArray(s map {_.toJson} toVector)
      case a: WomArray => write(a.value)
      case s: WomString => JsString(s.value)
      case i: WomInteger => JsNumber(i.value)
      case f: WomFloat => JsNumber(f.value)
      case f: WomSingleFile => JsString(f.value)
      case p: Path => JsString(p.pathAsString)
    }
    def read(value: JsValue) = throw new UnsupportedOperationException(s"Reading JSON not implemented: $value")
  }

  implicit object RawInputsJsonFormat extends JsonFormat[ExecutableInputMap] {
    def write(inputs: ExecutableInputMap) = JsObject(inputs map { case (k, v) => k -> v.toJson })
    def read(value: JsValue) = throw new UnsupportedOperationException(s"Reading JSON not implemented: $value")
  }

  def workflowJson: WorkflowJson = rawInputs.toJson.prettyPrint

  def deleteFile(path: Path) = path.delete()
}

object SampleWdl {

  object HelloWorld extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""
        |task hello {
        |  String addressee
        |  command {
        |    echo "Hello $${addressee}!"
        |  }
        |  output {
        |    String salutation = read_string(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |workflow wf_hello {
        |  call hello
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    val Addressee = "wf_hello.hello.addressee"
    val rawInputs = Map(Addressee -> "world")
    val OutputKey = "wf_hello.hello.salutation"
    val OutputValue = "Hello world!"
  }

  object GoodbyeWorld extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      """
        |task goodbye {
        |  command {
        |    sh -c "exit 1"
        |  }
        |  output {
        |    String out = read_string(stdout())
        |  }
        |}
        |
        |workflow wf_goodbye {
        |  call goodbye
        |}
      """.stripMargin

    val rawInputs = Map.empty[String, Any]
    val OutputKey = "goodbye.goodbye.out"
  }

  object EmptyString extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""
        |task hello {
        |  command {
        |    echo "Hello!"
        |  }
        |  output {
        |    String empty = ""
        |  }
        |  RUNTIME
        |}
        |
        |task goodbye {
        |  String emptyInputString
        |  command {
        |    echo "$${emptyInputString}"
        |  }
        |  output {
        |    String empty = read_string(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |workflow wf_hello {
        |  call hello
        |  call goodbye {input: emptyInputString=hello.empty }
        |  output {
        |   hello.empty
        |   goodbye.empty
        |  }
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    val rawInputs = Map.empty[String, Any]
    val outputMap = Map(
      "hello.hello.empty" -> WomString(""),
      "hello.goodbye.empty" -> WomString("")
    )
  }

  object CoercionNotDefined extends SampleWdl {
    override def workflowSource(runtime: String = "") = {
      s"""
        |task summary {
        |  String bfile
        |  command {
        |    ~/plink --bfile $${bfile} --missing --hardy --out foo --allow-no-sex
        |  }
        |  output {
        |    File hwe = "foo.hwe"
        |    File log = "foo.log"
        |    File imiss = "foo.imiss"
        |    File lmiss = "foo.lmiss"
        |  }
        |  meta {
        |    author: "Jackie Goldstein"
        |    email: "jigold@broadinstitute.org"
        |  }
        |}
        |
        |workflow test1 {
        |  call summary {
        |     input: bfile = bfile
        |  }
        |}
      """.stripMargin
    }

    override val rawInputs: ExecutableInputMap = Map("test1.bfile" -> "data/example1")
  }

  trait ThreeStepTemplate extends SampleWdl {
    override def workflowSource(runtime: String = "") = sourceString().replaceAll("RUNTIME", runtime)
    private val outputSectionPlaceholder = "OUTPUTSECTIONPLACEHOLDER"
    def sourceString(outputsSection: String = "") = {
      val withPlaceholders =
        s"""
        |task ps {
        |  command {
        |    ps
        |  }
        |  output {
        |    File procs = stdout()
        |  }
        |  RUNTIME
        |}
        |
        |task cgrep {
        |  String pattern
        |  File in_file
        |
        |  command {
        |    grep '$${pattern}' $${in_file} | wc -l
        |  }
        |  output {
        |    Int count = read_int(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |task wc {
        |  File in_file
        |  command {
        |    cat $${in_file} | wc -l
        |  }
        |  output {
        |    Int count = read_int(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |workflow three_step {
        |  call ps
        |  call cgrep {
        |    input: in_file = ps.procs
        |  }
        |  call wc {
        |    input: in_file = ps.procs
        |  }
        |  """ + outputSectionPlaceholder + """
        |}
        |
        """
      withPlaceholders.stripMargin.replace(outputSectionPlaceholder, outputsSection)
    }

    val PatternKey ="three_step.cgrep.pattern"
    override lazy val rawInputs = Map(PatternKey -> "...")
  }

  object ThreeStep extends ThreeStepTemplate

  object ThreeStepWithOutputsSection extends ThreeStepTemplate {
    override def workflowSource(runtime: String = "") = sourceString(outputsSection =
      """
        |output {
        | cgrep.count
        | wc.count
        |}
      """.stripMargin).replaceAll("RUNTIME", runtime)
  }

  object DeclarationsWorkflow extends SampleWdl {
    override def workflowSource(runtime: String): WorkflowSource =
      s"""
        |task cat {
        |  File file
        |  String? flags
        |  String? flags2 # This should be a workflow input
        |  command {
        |    cat $${flags} $${flags2} $${file}
        |  }
        |  output {
        |    File procs = stdout()
        |  }
        |}
        |
        |task cgrep {
        |  String str_decl
        |  String pattern
        |  File in_file
        |  command {
        |    grep '$${pattern}' $${in_file} | wc -l
        |  }
        |  output {
        |    Int count = read_int(stdout())
        |    String str = str_decl
        |  }
        |}
        |
        |workflow two_step {
        |  String flags_suffix
        |  String flags = "-" + flags_suffix
        |  String static_string = "foobarbaz"
        |  call cat {
        |    input: flags = flags
        |  }
        |  call cgrep {
        |    input: in_file = cat.procs
        |  }
        |}
      """.stripMargin

    private val fileContents =
      s"""first line
          |second line
          |third line
       """.stripMargin

    override val rawInputs: ExecutableInputMap = Map(
      "two_step.cgrep.pattern" -> "first",
      "two_step.cgrep.str_decl" -> "foobar",
      "two_step.cat.file" -> createCannedFile("canned", fileContents),
      "two_step.flags_suffix" -> "s"
    )
  }

  object CurrentDirectory extends SampleWdl {
    override def workflowSource(runtime: String): String =
      """
        |task whereami {
        |  command {
        |    pwd
        |  }
        |  output {
        |    String pwd = read_string(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |workflow wf_whereami {
        |  call whereami
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    override val rawInputs: Map[String, Any] = Map.empty
  }

  object ArrayIO extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""
        |task serialize {
        |  Array[String] strs
        |  command {
        |    cat $${write_lines(strs)}
        |  }
        |  output {
        |    String contents = read_string(stdout())
        |  }
        |  RUNTIME
        |}
        |
        |workflow wf {
        |  Array[String] strings = ["str1", "str2", "str3"]
        |  call serialize {
        |    input: strs = strings
        |  }
        |}
      """.stripMargin.replace("RUNTIME", runtime)
    override val rawInputs: Map[String, Any] = Map.empty
  }

  class ScatterWdl extends SampleWdl {
    val tasks = s"""task A {
      |  command {
      |    echo -n -e "jeff\nchris\nmiguel\nthibault\nkhalid\nruchi"
      |  }
      |  RUNTIME
      |  output {
      |    Array[String] A_out = read_lines(stdout())
      |  }
      |}
      |
      |task B {
      |  String B_in
      |  command {
      |    python -c "print(len('$${B_in}'))"
      |  }
      |  RUNTIME
      |  output {
      |    Int B_out = read_int(stdout())
      |  }
      |}
      |
      |task C {
      |  Int C_in
      |  command {
      |    python -c "print($${C_in}*100)"
      |  }
      |  RUNTIME
      |  output {
      |    Int C_out = read_int(stdout())
      |  }
      |}
      |
      |task D {
      |  Array[Int] D_in
      |  command {
      |    python -c "print($${sep = '+' D_in})"
      |  }
      |  RUNTIME
      |  output {
      |    Int D_out = read_int(stdout())
      |  }
      |}
      |
      |task E {
      |  command {
      |    python -c "print(9)"
      |  }
      |  RUNTIME
      |  output {
      |    Int E_out = read_int(stdout())
      |  }
      |}
    """.stripMargin

    override def workflowSource(runtime: String = "") =
      s"""$tasks
        |
        |workflow w {
        |  call A
        |  scatter (item in A.A_out) {
        |    call B {input: B_in = item}
        |    call C {input: C_in = B.B_out}
        |    call E
        |  }
        |  call D {input: D_in = B.B_out}
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    override lazy val rawInputs = Map.empty[String, String]
  }

  object SimpleScatterWdl extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""task echo_int {
        |  Int int
        |  command {echo $${int}}
        |  output {Int out = read_int(stdout())}
        |  RUNTIME_PLACEHOLDER
        |}
        |
        |workflow scatter0 {
        |  Array[Int] ints = [1,2,3,4,5]
        |  call echo_int as outside_scatter {input: int = 8000}
        |  scatter(i in ints) {
        |    call echo_int as inside_scatter {
        |      input: int = i
        |    }
        |  }
        |}
      """.stripMargin.replace("RUNTIME_PLACEHOLDER", runtime)

    override lazy val rawInputs = Map.empty[String, String]
  }

  object SimpleScatterWdlWithOutputs extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""task echo_int {
        |  Int int
        |  command {echo $${int}}
        |  output {Int out = read_int(stdout())}
        |}
        |
        |workflow scatter0 {
        |  Array[Int] ints = [1,2,3,4,5]
        |  call echo_int as outside_scatter {input: int = 8000}
        |  scatter(i in ints) {
        |    call echo_int as inside_scatter {
        |      input: int = i
        |    }
        |  }
        |  output {
        |    inside_scatter.*
        |  }
        |}
      """.stripMargin

    override lazy val rawInputs = Map.empty[String, String]
  }

  case class PrepareScatterGatherWdl(salt: String = UUID.randomUUID().toString) extends SampleWdl {
    override def workflowSource(runtime: String = "") = {
      s"""
        |#
        |# Goal here is to split up the input file into files of 1 line each (in the prepare) then in parallel call wc -w on each newly created file and count the words into another file then in the gather, sum the results of each parallel call to come up with
        |# the word-count for the fil
        |#
        |# splits each line into a file with the name temp_?? (shuffle)
        |task do_prepare {
        |    File input_file
        |    command {
        |        split -l 1 $${input_file} temp_ && ls -1 temp_?? > files.list
        |    }
        |    output {
        |        Array[File] split_files = read_lines("files.list")
        |    }
        |    RUNTIME
        |}
        |# count the number of words in the input file, writing the count to an output file overkill in this case, but simulates a real scatter-gather that would just return an Int (map)
        |task do_scatter {
        |    String salt
        |    File input_file
        |    command {
        |        # $${salt}
        |        wc -w $${input_file} > output.txt
        |    }
        |    output {
        |        File count_file = "output.txt"
        |    }
        |    RUNTIME
        |}
        |# aggregate the results back together (reduce)
        |task do_gather {
        |    Array[File] input_files
        |    command <<<
        |        cat $${sep = ' ' input_files} | awk '{s+=$$1} END {print s}'
        |    >>>
        |    output {
        |        Int sum = read_int(stdout())
        |    }
        |    RUNTIME
        |}
        |workflow sc_test {
        |    call do_prepare
        |    scatter(f in do_prepare.split_files) {
        |        call do_scatter {
        |            input: input_file = f
        |        }
        |    }
        |    call do_gather {
        |        input: input_files = do_scatter.count_file
        |    }
        |}
      """.stripMargin.replace("RUNTIME", runtime)
    }

    val contents =
        """|the
           |total number
           |of words in this
           |text file is 11
           |""".stripMargin

    override lazy val rawInputs = Map(
      "sc_test.do_prepare.input_file" -> createCannedFile("scatter", contents).pathAsString,
      "sc_test.do_scatter.salt" -> salt)
  }

  object FileClobber extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""task read_line {
        |  File in
        |  command { cat $${in} }
        |  output { String out = read_string(stdout()) }
        |}
        |
        |workflow two {
        |  call read_line as x
        |  call read_line as y
        |}
      """.stripMargin

    val tempDir1 = DefaultPathBuilder.createTempDirectory("FileClobber1")
    val tempDir2 = DefaultPathBuilder.createTempDirectory("FileClobber2")
    val firstFile = createFile(name = "file.txt", contents = "first file.txt", dir = tempDir1)
    val secondFile = createFile(name = "file.txt", contents = "second file.txt", dir = tempDir2)

    override val rawInputs = Map(
      "two.x.in" -> firstFile.pathAsString,
      "two.y.in" -> secondFile.pathAsString
    )
  }

  object FilePassingWorkflow extends SampleWdl {
    override def workflowSource(runtime: String): WorkflowSource =
      s"""task a {
        |  File in
        |  String out_name = "out"
        |
        |  command {
        |    cat $${in} > $${out_name}
        |  }
        |  RUNTIME
        |  output {
        |    File out = "out"
        |    File out_interpolation = "$${out_name}"
        |    String contents = read_string("$${out_name}")
        |  }
        |}
        |
        |workflow file_passing {
        |  File f
        |
        |  call a {input: in = f}
        |  call a as b {input: in = a.out}
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    private val fileContents = s"foo bar baz"

    override val rawInputs: ExecutableInputMap = Map(
      "file_passing.f" -> createCannedFile("canned", fileContents).pathAsString
    )
  }

  /**
    * @param salt - an arbitrary value that will be added as
    *               a BASH comment on the command, this is so
    *               tests can have control over call caching
    *               for this workflow.  i.e. so one test can't
    *               call cache to another test if the seeds are
    *               different
    */
  case class CallCachingWorkflow(salt: String) extends SampleWdl {
    override def workflowSource(runtime: String): WorkflowSource =
      s"""task a {
        |  File in
        |  String out_name = "out"
        |  String salt
        |
        |  command {
        |    # $${salt}
        |    echo "Something"
        |    cat $${in} > $${out_name}
        |  }
        |  RUNTIME
        |  output {
        |    File out = "out"
        |    File out_interpolation = "$${out_name}"
        |    String contents = read_string("$${out_name}")
        |    Array[String] stdoutContent = read_lines(stdout())
        |  }
        |}
        |
        |workflow file_passing {
        |  File f
        |
        |  call a {input: in = f}
        |  call a as b {input: in = a.out}
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    private val fileContents = s"foo bar baz"

    override val rawInputs: ExecutableInputMap = Map(
      "file_passing.f" -> createCannedFile("canned", fileContents).pathAsString,
      "file_passing.a.salt" -> salt,
      "file_passing.b.salt" -> salt
    )
  }

  object CallCachingHashingWdl extends SampleWdl {
    override def workflowSource(runtime: String): WorkflowSource =
      s"""task t {
        |  Int a
        |  Float b
        |  String c
        |  File d
        |
        |  command {
        |    echo "$${a}" > a
        |    echo "$${b}" > b
        |    echo "$${c}" > c
        |    cat $${d} > d
        |  }
        |  output {
        |    Int w = read_int("a") + 2
        |    Float x = read_float("b")
        |    String y = read_string("c")
        |    File z = "d"
        |  }
        |  RUNTIME
        |}
        |
        |workflow w {
        |  call t
        |}
      """.stripMargin.replace("RUNTIME", runtime)

    val tempDir = DefaultPathBuilder.createTempDirectory("CallCachingHashingWdl")
    val cannedFile = createCannedFile(prefix = "canned", contents = "file contents", dir = Option(tempDir))
    override val rawInputs = Map(
      "w.t.a" -> WomInteger(1),
      "w.t.b" -> WomFloat(1.1),
      "w.t.c" -> WomString("foobar"),
      "w.t.d" -> WomSingleFile(cannedFile.pathAsString)
    )
  }

  object ExpressionsInInputs extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""task echo {
        |  String inString
        |  command {
        |    echo $${inString}
        |  }
        |
        |  output {
        |    String outString = read_string(stdout())
        |  }
        |}
        |
        |workflow wf {
        |  String a1
        |  String a2
        |  call echo {
        |   input: inString = a1 + " " + a2
        |  }
        |  call echo as echo2 {
        |    input: inString = a1 + " " + echo.outString + " " + a2
        |  }
        |}
      """.stripMargin
    override val rawInputs = Map(
      "wf.a1" -> WomString("hello"),
      "wf.a2" -> WomString("world")
    )
  }

  object WorkflowFailSlow extends SampleWdl {
    override def workflowSource(runtime: String = "") =
      s"""
task shouldCompleteFast {
        |    Int a
        |    command {
        |        echo "The number was: $${a}"
        |    }
        |    output {
        |        Int echo = a
        |    }
        |}
        |
        |task shouldCompleteSlow {
        |    Int a
        |    command {
        |        echo "The number was: $${a}"
        |        # More than 1 so this should finish second
        |        sleep 2
        |    }
        |    output {
        |        Int echo = a
        |    }
        |}
        |
        |task failMeSlowly {
        |    Int a
        |    command {
        |        echo "The number was: $${a}"
        |        # Less than 2 so this should finish first
        |        sleep 1
        |        ./NOOOOOO
        |    }
        |    output {
        |        Int echo = a
        |    }
        |}
        |
        |task shouldNeverRun {
        |    Int a
        |    Int b
        |    command {
        |        echo "You can't fight in here - this is the war room $${a + b}"
        |    }
        |    output {
        |        Int echo = a
        |    }
        |}
        |
        |workflow wf {
        |    call shouldCompleteFast as A { input: a = 5 }
        |    call shouldCompleteFast as B { input: a = 5 }
        |
        |    call failMeSlowly as ohNOOOOOOOO { input: a = A.echo }
        |    call shouldCompleteSlow as C { input: a = B.echo }
        |
        |    call shouldNeverRun as D { input: a = ohNOOOOOOOO.echo, b = C.echo }
        |    call shouldCompleteSlow as E { input: a = C.echo }
        |}
      """.stripMargin

    val rawInputs = Map(
      "w.x.a" -> 5
    )
  }
}
