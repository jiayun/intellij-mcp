export class Worker { work() { return 0; } }
export class Concrete extends Worker { work() { return callee(); } }
export function callee() { return 1; }
