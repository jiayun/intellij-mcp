<template><div /></template>
<script lang="ts">
interface Worker { work(): number; }
class Concrete implements Worker { work(): number { return callee(); } }
function callee(): number { return 1; }
function caller(): number { return callee(); }
export default {};
</script>
