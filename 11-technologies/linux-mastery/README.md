# Linux Command Mastery — The Backend Debugging Toolbelt

> **Mental model:** backend interviews (and on-call reality) assume you can land on a misbehaving box and answer four questions fast: **what is this machine doing? what is this process doing? where did the resources go? what does the log say?** This note organizes commands by *those questions*, not alphabetically — because that's how you'll actually reach for them. Drill the incident walkthrough in §6 until it's muscle memory.

---

## 1. What is this machine doing? (the 60-second triage)

```bash
uptime                  # load averages 1/5/15min — load > core count = queuing
top / htop              # live CPU/mem per process; press '1' for per-core, 'M'/'P' to sort
vmstat 1                # run queue (r), swapping (si/so — any swapping = pain), io wait (wa)
free -h                 # memory: watch 'available', not 'free' (page cache is reclaimable!)
df -h                   # disk full? (#1 boring outage cause)
iostat -x 1             # per-disk: %util near 100 = disk-bound
```

Reading `top` correctly is a skill: high `%wa` (I/O wait) means the disk, not the CPU, is the bottleneck; high `load` with idle CPU means processes blocked on I/O — [saturation signals](../../10-security-observability/observability/README.md), read from the source.

## 2. What is this process doing?

```bash
ps aux | grep java              # find it; ps -o pid,ppid,rss,pcpu,etime -p <pid> for detail
lsof -p <pid>                   # every open file, socket, pipe — "what is it touching?"
lsof -i :8080                   # who owns this port?
strace -p <pid> -c              # syscall summary — where kernel time goes (careful in prod)
jstack <pid>                    # JVM: thread dump — find the blocked/deadlocked threads
jmap -heap <pid> / jstat -gc    # JVM: heap and GC behavior
kill -TERM <pid>                # graceful (app can drain); -KILL only as last resort
nice / ionice / taskset         # priority & CPU pinning
```

## 3. Where is the network? 

```bash
ss -tlnp                        # listening sockets + owning process (netstat's successor)
ss -s                           # socket summary — TIME_WAIT explosions, connection counts
ping / traceroute / mtr host    # reachability and where latency lives
dig api.example.com +short      # DNS — always suspect DNS
curl -v -w '%{time_connect} %{time_starttransfer} %{time_total}\n' -o /dev/null -s URL
                                # TLS + TTFB + total — a one-line latency profiler
tcpdump -i any port 8080 -w cap.pcap    # capture for the hard cases
```

## 4. What does the log say? (text-processing pipelines)

```bash
tail -f app.log | grep --line-buffered ERROR        # live errors
grep -c "ERROR" app.log                             # how many?
grep "ERROR" app.log | awk '{print $7}' | sort | uniq -c | sort -rn | head
                       # top error endpoints — the awk/sort/uniq -c/sort -rn idiom IS the interview
awk '$9 >= 500 {print $7}' access.log | sort | uniq -c | sort -rn | head   # 5xx by URL
awk '{sum+=$10; n++} END {print sum/n}' access.log  # avg response size/time column
sed -n '1000,1020p' app.log                         # slice by line numbers
journalctl -u myapp -S "10 min ago"                 # systemd service logs
zgrep "trace-id-123" app.log.*.gz                   # search rotated logs
```

The composable-pipeline idiom (`grep | awk | sort | uniq -c | sort -rn | head`) answers 80% of "analyze this log" tasks — practice it until you type it without thinking.

## 5. Files, permissions, transfer, persistence

```bash
find /var/log -size +100M -mtime -2          # big recent files (disk-full hunts)
du -sh */ | sort -rh | head                  # what's eating this directory
chmod 640 f; chown app:app f; umask          # permissions (rwx = 421)
ln -s target link                            # symlinks (how "current release" pointers work)
scp file host:; rsync -avz --progress dir/ host:dir/    # rsync = delta transfer, resumable
tar czf backup.tgz dir/; tar xzf backup.tgz
nohup ./job & / tmux / screen                # survive your SSH disconnect
crontab -e                                   # "0 3 * * * /backup.sh" — min hr dom mon dow
systemctl status/restart myapp; systemctl enable myapp   # services that survive reboot
```

## 6. The incident walkthrough (drill this)

*"API is slow"* → `uptime` (load?) → `top` (which process? CPU or %wa?) → if JVM: `jstack` (blocked threads?) + `jstat -gc` (GC storm?) → `ss -s` (connection pile-up?) → `df -h` (disk full → writes blocking?) → `tail`+`grep` the app log for errors, `awk` the access log for which endpoint's latency moved → correlate with the last deploy ([deployment patterns](../../07-microservices/deployment-patterns/README.md)). Being able to *narrate* this sequence is an interview answer in itself.

## 7. The implementation

[`LinuxToolbox.java`](LinuxToolbox.java) re-implements the core text-processing pipeline **in Java** — `grep`, `sort | uniq -c | sort -rn`, `tail -f`, and an access-log analyzer (top endpoints by 5xx count and p99 latency) — plus a `ProcessBuilder`-based runner that executes real commands with timeouts and captured output (how deploy scripts and health checks actually shell out from Java). Processing a log both ways (shell one-liner vs Java) cements what each pipeline stage does.

## 8. Interview soundbites

- "Load average above core count means queuing; high I/O-wait means the disk is the bottleneck, not the CPU."
- "`free` is misleading — page cache is reclaimable; read the 'available' column."
- "`grep | awk | sort | uniq -c | sort -rn` — the five-stage idiom that answers most log questions."
- "`kill -TERM` first, always — SIGKILL skips connection draining and cleanup."

**Related:** [Observability](../../10-security-observability/observability/README.md) · [Docker](../docker/README.md) · [Nginx](../nginx/README.md) · [Tomcat ops](../../12-app-servers/tomcat/README.md)
