import { callee } from "./worker";
export function caller() { return callee(); }
