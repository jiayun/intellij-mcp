interface Worker { fun work(): Int }
class Concrete : Worker { override fun work() = callee() }
fun callee(): Int = 1
fun callee(value: Int): Int = value
fun otherCaller(): Int = callee(2)
