#!/bin/sh
# SWE-bench-style test: passes when greet.txt contains exactly "Hello, world!".
grep -qx 'Hello, world!' greet.txt
