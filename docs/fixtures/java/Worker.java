public interface Worker { int work(); }
class Concrete implements Worker { public int work() { return Calls.callee(); } }
class Calls { static int callee() { return 1; } }
