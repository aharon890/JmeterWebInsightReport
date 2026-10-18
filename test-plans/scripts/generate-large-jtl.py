#!/usr/bin/env python3
"""
Generate a large JTL (CSV) file with 200,000+ sample rows for testing
external data mode in the Web Insight Report plugin.

Produces 10 different sampler names across a 10-minute time span with
a mix of success and error responses.
"""

import csv
import random
import sys
import os
import time

SAMPLER_NAMES = [
    "GET /api/users",
    "GET /api/orders",
    "POST /api/checkout",
    "GET /api/products",
    "GET /api/search",
    "PUT /api/cart",
    "DELETE /api/cart/item",
    "GET /api/categories",
    "POST /api/login",
    "GET /api/dashboard",
]

HEADER = [
    "timeStamp", "elapsed", "label", "responseCode", "responseMessage",
    "threadName", "dataType", "success", "failureMessage", "bytes",
    "sentBytes", "grpThreads", "allThreads", "URL", "Latency", "IdleTime", "Connect"
]

NUM_ROWS = 210_000
DURATION_MS = 10 * 60 * 1000  # 10 minutes
ERROR_RATE = 0.03  # 3% errors
NUM_THREADS = 50

def generate_jtl(output_path):
    start_ts = int(time.time() * 1000) - DURATION_MS  # start 10 min ago
    random.seed(42)  # reproducible

    with open(output_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(HEADER)

        for i in range(NUM_ROWS):
            ts = start_ts + int((i / NUM_ROWS) * DURATION_MS) + random.randint(-500, 500)
            label = random.choice(SAMPLER_NAMES)

            is_error = random.random() < ERROR_RATE
            if is_error:
                code = random.choice(["500", "502", "503", "400", "404", "429"])
                message = {
                    "500": "Internal Server Error",
                    "502": "Bad Gateway",
                    "503": "Service Unavailable",
                    "400": "Bad Request",
                    "404": "Not Found",
                    "429": "Too Many Requests",
                }.get(code, "Error")
                success = "false"
                failure_msg = f"Response code {code}"
            else:
                code = "200"
                message = "OK"
                success = "true"
                failure_msg = ""

            # Response time distribution: mostly 50-300ms, some slow (up to 2000ms)
            if random.random() < 0.05:
                elapsed = random.randint(500, 2000)
            else:
                elapsed = random.randint(30, 350)

            latency = max(1, elapsed - random.randint(0, 20))
            connect = random.randint(1, 15)
            idle_time = 0
            resp_bytes = random.randint(200, 15000)
            sent_bytes = random.randint(50, 500)

            thread_num = (i % NUM_THREADS) + 1
            thread_name = f"Thread Group 1-{thread_num}"
            active_threads = min(NUM_THREADS, max(1, int(NUM_THREADS * min(1.0, (i / NUM_ROWS) * 3))))

            url = f"http://localhost:8080{label.split(' ', 1)[1]}"

            writer.writerow([
                ts, elapsed, label, code, message,
                thread_name, "text", success, failure_msg,
                resp_bytes, sent_bytes, active_threads, active_threads,
                url, latency, idle_time, connect
            ])

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Generated {NUM_ROWS} rows -> {output_path} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    output = sys.argv[1] if len(sys.argv) > 1 else "large-test-data.jtl"
    generate_jtl(output)
