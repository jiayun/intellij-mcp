pub mod caller;
pub trait Worker { fn work(&self) -> i32; }
pub struct Concrete;
impl Worker for Concrete { fn work(&self) -> i32 { callee() } }
pub fn callee() -> i32 { 1 }
pub fn recursive() { recursive(); }
