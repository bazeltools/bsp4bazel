//> using scala "3.3"
//> using dep "com.lihaoyi::os-lib:0.9.2"
//> using test.dep "org.scalameta::munit::0.7.29"

class BazelRulesTest extends munit.FunSuite:

  def testBazelRules[T](dir: os.Path)(implicit loc: munit.Location): Unit = {
    test(s"should output bsp_target_info.json for $dir") {
      os.proc(
        dir / "bazel",
        "build",
        "--aspects",
        "@bsp4bazel-rules//:bsp_target_info_aspect.bzl%bsp_target_info_aspect",
        "--output_groups",
        "bsp_output",
        "//..."
      ).call(cwd = dir)
    
      assert(os.exists(dir / "bazel-bin" / "src" / "main_run_bsp_target_info.json"))
      assert(os.exists(dir / "bazel-bin" / "src" / "example" / "example_bsp_target_info.json"))
      assert(os.exists(dir / "bazel-bin" / "src" / "example" / "foo" / "foo_bsp_target_info.json"))
    }
  
    test(s"should output bsp_workspace_deps.json for $dir") {
      os.proc(
        dir / "bazel",
        "build",
        "//:bsp_workspace_deps",
      ).call(cwd = dir)
    
      assert(os.exists(dir / "bazel-bin" / "bsp_workspace_deps.json"))
    
      val json = os.read(dir / "bazel-bin" / "bsp_workspace_deps.json")
      assert(json.contains("semanticdb-scalac"))
      assert(json.contains("scala-reflect"))
      assert(json.contains("scala-library"))
      assert(json.contains("scala-compilerz"))
    }
  }

  test("should build examples/simple-no-errors") {
    val workingDir = os.pwd / "examples" / "simple-no-errors"

    os.proc(workingDir / "bazel", "build", "//...")
      .call(cwd = workingDir)
  }

  test("should fail to build examples/simple-with-errors") {
    val workingDir = os.pwd / "examples" / "simple-with-errors"

    val result = os
      .proc(workingDir / "bazel", "build", "//...")
      .call(cwd = workingDir, check = false)

    assertEquals(result.exitCode, 1)
  }

  testBazelRules(os.pwd / "examples" / "simple-no-errors")
  testBazelRules(os.pwd / "examples" / "simple-with-errors")
