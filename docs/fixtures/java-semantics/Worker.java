public interface Worker { int work(); }
class Concrete implements Worker { public int work() { return Calls.callee(); } }
class Calls { static int callee(int value) { return value; } static int callee() { return 1; } }
class Alternate { int otherCaller() { return Calls.callee(2); } }
