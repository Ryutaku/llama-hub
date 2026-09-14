#!/bin/bash
cd "$(dirname "$0")"
if [ -f app.pid ]; then
  PID=$(cat app.pid)
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    for i in $(seq 1 30); do
      if ! kill -0 "$PID" 2>/dev/null; then break; fi
      sleep 1
    done
    if kill -0 "$PID" 2>/dev/null; then
      kill -9 "$PID"
      echo "force stopped pid=$PID"
    else
      echo "stopped pid=$PID"
    fi
  else
    echo "not running (stale pid)"
  fi
  rm -f app.pid
else
  echo "no pid file"
fi
