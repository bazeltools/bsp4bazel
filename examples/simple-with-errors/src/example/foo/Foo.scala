package example.foo

trait Before

// ERROR
case class Foo(i: IntZ) extends BeforeT

case class After(a: String)
