# Quickstart: checking the k6 tab on K1 and K2

From the repository root, with the JAR built and deployed.

```bash
./test-plans/scripts/run-regression.sh     # builds K1 and K2 among the fixtures, then every browser spec
```

Then open `test-results/K2/K2-report.html` and go to the k6 tab:

1. The strip reads "Thresholds: 1 of 50 failed: Login p(95)<=600".
2. Thresholds: the `http_req_duration` group is open; Login's row shows `779.72 ms` and "179.72 ms over (30%)". The
   41 always-true thresholds are folded.
3. Group by Request: Login's thresholds sit together. Click "Login": the Requests tab opens on Login's row with its
   detail panel.
4. HTTP timings: 13 rows under 3 groups. Sort by Waiting: Login is first among the checkout flow's requests (182.63 ms
   on average). Switch to p(95): every cell changes.
5. Reload the page: the grouping and statistic are as you left them.
6. Open any JMeter fixture (E02): no k6 tab, and nothing else changed.
