import { callee } from './worker';
export function caller(): number { return callee(); }
