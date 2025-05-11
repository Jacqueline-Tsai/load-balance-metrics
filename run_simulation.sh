#!/bin/bash

# Start the Cloud process
java Cloud 3000 ../lib/db1.txt c-1000-sss 0 &

# Wait for a moment to ensure Cloud is up and running
sleep 2

# Start the Server process
java Server 127.0.0.1 3000 1

# Wait for both processes
wait 