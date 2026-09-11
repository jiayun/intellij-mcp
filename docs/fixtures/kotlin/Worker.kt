interface Worker { fun work(): Int }
class Concrete : Worker { override fun work() = callee() }
fun callee(): Int = 1
