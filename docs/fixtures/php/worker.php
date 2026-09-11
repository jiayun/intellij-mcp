<?php
interface Worker { public function work(): int; }
trait Extra { public function extra(): int { return 2; } }
class Concrete implements Worker { use Extra; public function work(): int { return callee(); } }
function callee(): int { return 1; }
