#!/usr/bin/env bash
# Records fixture K1. It runs INSIDE perf hub's k6 image (k6 2.4.0 plus bash and socat), with this folder mounted at
# /w and K1/ at /out:
#   docker run --rm -v "<K1/record>:/w" -v "<K1>:/out" --entrypoint bash perf-hub-k6:2.4.0 /w/record.sh
# Three stub servers answer: 8080 OK after 30 ms, 8081 a 500, 8082 a redirect to 8080. Port 8099 refuses.
# The responses are written here with CRLF line ends, so the repository holds LF text only.
set -u
printf 'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok' > /tmp/ok.txt
printf 'HTTP/1.1 500 Internal Server Error\r\nContent-Length: 4\r\nConnection: close\r\n\r\nfail' > /tmp/err.txt
printf 'HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:8080/landed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n' > /tmp/redir.txt
socat TCP-LISTEN:8080,fork,reuseaddr SYSTEM:'sleep 0.03; cat /tmp/ok.txt' 2>/dev/null &
socat TCP-LISTEN:8081,fork,reuseaddr SYSTEM:'cat /tmp/err.txt' 2>/dev/null &
socat TCP-LISTEN:8082,fork,reuseaddr SYSTEM:'cat /tmp/redir.txt' 2>/dev/null &
sleep 0.5
# The two settings perf hub gives every k6 run (perf hub spec 010, FR-002)
export K6_CSV_TIME_FORMAT=unix_milli
export K6_SYSTEM_TAGS=proto,subproto,status,method,url,name,group,check,error,error_code,tls_version,scenario,service,expected_response,vu,iter
k6 run -q --out csv=/out/results.csv.gz /w/shapes.js > /tmp/k6-console.txt 2>&1
rc=$?
mv /w/summary.json /out/summary.json
echo "k6 exit $rc (99 = a threshold failed, which K1 wants)"
