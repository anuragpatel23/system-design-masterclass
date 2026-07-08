# 09 — Interview Prep

> Everything else in this vault is *knowledge*; this section is *delivery*. System design interviews are a performance with a rubric: the interviewer is scoring how you scope an ambiguous problem, whether your numbers drive your decisions, whether you name trade-offs unprompted, and whether you can go one level deeper on demand. Strong candidates with weak process lose to average candidates with strong process — this section is the process.

## Contents

| Note | What it gives you |
|---|---|
| [The RESHADED Framework](interview-framework.md) | The step-by-step structure for any "design X" question — what to do minute by minute in a 45-60 minute round |
| [Capacity Estimation](capacity-estimation.md) | The numbers to memorize (latency, throughput, storage) and the back-of-envelope method that turns them into design decisions |
| [Trade-off Cheat Sheet](tradeoff-cheatsheet.md) | The recurring trade-off axes — one page to re-read the morning of the interview |
| [Question Bank](question-bank.md) | The classic questions mapped to this vault's designs, with what each is really testing |

## The five behaviors interviewers score (know the rubric)

1. **Requirements discipline** — you scope before you build: functional requirements as a short list, non-functional requirements as *numbers* (DAU, QPS, latency targets, consistency needs), and you explicitly say what's out of scope.
2. **Numbers drive decisions** — estimation isn't a ritual to rush past; it's where you *earn* your architecture. "60k writes/sec means a single primary is out, so we shard by X" is the sentence structure that scores.
3. **Trade-offs stated unprompted** — every component choice names its cost and why it's acceptable *here*. The rubric literally has a box for this.
4. **Depth on demand** — when the interviewer says "tell me more about the cache invalidation," you can go two levels down (that's sections 01–08's job) and come back up without losing the thread.
5. **Driving the conversation** — you narrate your plan, check in at each phase ("want me to go deeper on the data model, or move to scaling?"), and treat the interviewer as a collaborator, not an examiner.

## How to practice (the part everyone skips)

Reading designs feels like progress; it isn't. The skill is produced by **doing reps out loud**: pick a question from the [question bank](question-bank.md), set a 40-minute timer, stand at a whiteboard (or blank doc), and run the [framework](interview-framework.md) end to end while speaking. Then grade yourself against the five behaviors above. Three timed reps beat thirty passive reads — the failure mode this kills is knowing everything and structuring nothing.

Previous: [08 — API Design](../08-api-design/README.md) · Next: [10 — Security & Observability](../10-security-observability/README.md)
