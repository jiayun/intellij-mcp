abstract class Worker { int work(); }
mixin Extra { int extra() => 2; }
class Concrete extends Worker with Extra { int work() => callee(); }
int callee() => 1;
