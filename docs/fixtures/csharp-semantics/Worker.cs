public interface IWorker { int Work(); }
public class ConcreteWorker : IWorker {
    public int Work() { return Callee(); }
    public static int Callee() { return 1; }
}
