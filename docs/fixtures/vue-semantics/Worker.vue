<template><div /></template>
<script setup lang="ts">
interface Worker { work(): number; }
class Concrete implements Worker { work(): number { return callee(); } }
function callee(): number { return 1; }
function caller(): number { return callee(); }
</script>
