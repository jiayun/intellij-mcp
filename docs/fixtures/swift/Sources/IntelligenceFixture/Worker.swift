public protocol Worker {
    func work() -> Int
}
public struct ConcreteWorker: Worker {
    public init() {}
    public func work() -> Int { return 42 }
}
public func callee() -> Int { return 1 }
