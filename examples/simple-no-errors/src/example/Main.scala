package example

import example.foo.Foo
import example.foo.Bar

object Main extends App {
  val f = Foo(1)
  val b = Bar("a")
  Console.println(s"Hello World. I have $f, $b")
}
