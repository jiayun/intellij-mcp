export interface Worker { work(): number; }
export class Concrete implements Worker { work(): number { return callee(); } }
export function callee(): number { return 1; }
