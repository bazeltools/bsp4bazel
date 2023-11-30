package example.foo

trait Before

// ERROR
case class Foo(i: Int) extends Before

case class After(a: String)
