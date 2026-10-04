# Qwen3.8-27B GAIA Level 1 Failure Analysis — 16 Failed Tasks

**Date:** 2026-10-04
**Model:** Qwen3.8-27B (+DeepSeek-V4-Pro smart-routed)
**Method:** Read full iteration traces from receipt JSON files, not just final outputs.

---

## Summary by Root Cause Category

| Category | Count | Task IDs |
|----------|-------|----------|
| (b) Failed to find info (retrieval) | 7 | 305ac316, 5d0080cb, 72e110e7, 7673d772, b415aba4, dc22a632, a1e91b78* |
| (e) Formatting/extraction issue | 7 | 3cef3a44, 4b6bb5f7, bda648d7, 46719c30, cf106601, e142056d, ec09fa32 |
| (a) Wrong reasoning with right info | 5 | 46719c30, a1e91b78, d0633230, e142056d, ec09fa32 |
| (c) Tool call format errors | 3 | 5d0080cb, c365c1c7, cf106601 |
| (d) Gave up too early / empty | 4 | 5d0080cb, 7673d772, c365c1c7, dc22a632 |

*Many tasks have multiple overlapping causes. a1e91b78 is counted in both (b) and (a).*

**Notable:** gaia-a1e91b78 and gaia-ec09fa32 **PASSED with DeepSeek-V4-Pro** but FAILED with Qwen. These are model-specific reasoning gaps, not harness issues.

---

## Category (b): Failed to Find the Info — 7 tasks

### gaia-305ac316 — Polish Everybody Loves Raymond dub actor
- **Q:** Who did the actor who played Ray in the Polish-language version of Everybody Loves Raymond play in Magda M.? Give only the first name.
- **Expected:** `Wojciech` | **Got:** `Adam`
- **Trace:** 16 iterations, 70,274 tokens. Searched extensively for Polish dub cast ("Wszyscy kochają Raymonda" dubbing, "Ray Barone" dubbing polski, etc.). Got the original US cast repeatedly, never the Polish voice actor. Multiple web_fetch timeouts (filmweb.pl, wordplays.com). Eventually searched for Bartłomiej Kasprzykowski (who plays Wojciech in Magda M. per FDB) and answered "Adam" — a wrong guess.
- **Root cause:** Search never surfaced the Polish dub actor. The information exists (Polish dubbing databases) but the search queries didn't find it.
- **Harness fix:** Detect search loops — 16 iterations of similar queries with no new information. After N failed distinct queries on the same sub-question, force a strategy change or cut losses. 70k tokens for zero progress is wasteful.

### gaia-5d0080cb — Leicester fish bag volume
- **Q:** What was the volume in m^3 of the fish bag calculated in the University of Leicester paper "Can Hiccup Supply Enough Fish to Maintain a Dragon's Diet?"
- **Expected:** `0.1777` | **Got:** `0.7`
- **Trace:** 13 iterations. Found the paper URL immediately. PDF download via `run` timed out (60s). Then made **multiple malformed tool calls** (`web_fetch({'arguments': {'url': ...}})` instead of `web_fetch({'url': ...})`). Tried `curl` (not on allowlist). Gave up and guessed "0.7" from partial context.
- **Root cause:** (b) PDF inaccessible + (c) tool format errors + (d) gave up with guess.
- **Harness fix:** (1) Normalize the `{'arguments': {...}}` wrapper — unwrap it automatically instead of erroring. (2) The web_fetch tool should handle PDF URLs better or the agent should have a PDF text extraction path.

### gaia-72e110e7 — DDC 633 BASE unknown language article
- **Q:** Under DDC 633 on Bielefeld University Library's BASE, as of 2020, from what country was the unknown language article with a flag unique from the others?
- **Expected:** `Guatemala` | **Got:** `Turkey`
- **Trace:** 17 iterations. base-search.net returned HTTP 403, then timeouts. Tried Wayback Machine (no snapshots). Found a GitHub repo with the task definition. Tried to download a JSON file with the answer but `curl` not allowed, `urllib` timed out. Answered "Turkey" — appears to be a hallucinated guess.
- **Root cause:** The target website (base-search.net) was completely inaccessible (403 + timeouts). The agent could not retrieve the actual data.
- **Harness fix:** This is primarily a network/infrastructure issue. However, the harness should prevent hallucinating an answer when the source is inaccessible — better to output empty or "unable to retrieve" than a made-up country.

### gaia-7673d772 — Cornell Law rule amendment
- **Q:** On Cornell Law School website's legal information institute, under the fifth section of federal rules alphabetically, what word was deleted in the last amendment to the first rule in the article that has "witnesses" in the most titles as of 2021?
- **Expected:** `inference` | **Got:** `` (empty)
- **Trace:** 9 iterations. Multiple web_fetch timeouts on law.cornell.edu. Managed to fetch some pages but couldn't navigate to the specific amendment history. Gave up with empty output.
- **Root cause:** (b) Network timeouts + (d) gave up with empty output.
- **Harness fix:** The "forced answer" mechanism should have triggered — empty output is never acceptable. If the agent has partial information, it should provide a best-effort answer.

### gaia-b415aba4 — Nature Scientific Reports nano-compound
- **Q:** In Nature journal's Scientific Reports conference proceedings from 2012, in the article that did not mention plasmons or plasmonics, what nano-compound is studied? Don't use the prefix nano in your answer if there is one.
- **Expected:** `diamond` | **Got:** `graphene oxide`
- **Trace:** 16 iterations. Searched repeatedly for "Scientific Reports 2012 conference proceedings" with various terms. Got irrelevant Wikipedia results every time. Never found the actual proceedings. Answered "graphene oxide" — a guess based on search results mentioning graphene.
- **Root cause:** Search strategy was completely ineffective. 16 iterations of similar queries yielding no relevant results.
- **Harness fix:** Same as 305ac316 — detect when searches aren't yielding results and force a different approach. The agent should recognize "I'm getting the same irrelevant results" and try a fundamentally different query strategy.

### gaia-dc22a632 — Ali Khan book title
- **Q:** What was the complete title of the book in which two James Beard Award winners recommended the restaurant where Ali Khan enjoyed a New Mexican staple in his cost-conscious TV show that started in 2015?
- **Expected:** `Five Hundred Things To Eat Before It's Too Late: and the Very Best Places to Eat Them` | **Got:** Best-effort guess `The Best Restaurants in New Mexico`
- **Trace:** 17 iterations. Correctly identified the show (Cheap Eats, 2015) and the staple (Carne Adovada, Albuquerque). But could not identify the specific restaurant. tvfoodmaps.com returned HTTP 429. Ran out of steps and provided a guessed answer with a disclaimer.
- **Root cause:** (b) Could not identify the restaurant + (d) ran out of steps.
- **Harness fix:** This is a legitimately hard multi-hop question. The harness can't fix the 429. But 17 iterations is the max — the agent was making progress (identified show, staple, city) but needed more steps for the final hops.

### gaia-a1e91b78 — YouTube bird species (PASSED with DeepSeek-Pro)
- **Q:** In the video https://www.youtube.com/watch?v=L1vXCYZAYYM, what is the highest number of bird species to be on camera simultaneously?
- **Expected:** `3` | **Got:** `31`
- **Trace:** 12 iterations. YouTube returned HTTP 429 (cannot watch video). Searched for Guinness World Record. Found a wordplays.com crossword solver link. Answered "31" — likely misread from the crossword page or confused with a different record.
- **Root cause:** (b) YouTube inaccessible + (a) wrong reasoning — picked "31" from an unreliable source.
- **Harness fix:** Model-specific gap (DeepSeek-Pro got this right). The harness can't fix YouTube 429s.

---

## Category (e): Formatting/Extraction Issues — 7 tasks

These are the most frustrating failures — the agent had the right information but the output was malformed.

### gaia-3cef3a44 — Botany grocery list
- **Q:** [List of foods] Create a list of just the vegetables, excluding botanical fruits. Alphabetize, comma-separated.
- **Expected:** `broccoli, celery, fresh basil, lettuce, sweet potatoes`
- **Got:** `basil, broccoli, celery, lettuce, sweet potatoes`
- **Trace:** 2 iterations, no tools. The agent correctly identified the vegetables but wrote "basil" instead of "fresh basil" (the exact name from the input list).
- **Root cause:** Did not preserve the exact item name from the source list.
- **Harness fix:** Add a verification step: "Did you use the exact strings from the input list?" This is a simple string-matching check the harness could enforce.

### gaia-4b6bb5f7 — Doctor Who Heaven Sent script
- **Q:** In Series 9, Episode 11 of Doctor Who, what is the location called in the official script? Give the setting exactly as it appears in the first scene heading.
- **Expected:** `THE CASTLE`
- **Got:** `The first scene heading in the official script is "INT. THE CASTLE - DAY". The setting as it appears there is "THE CASTLE".\n\nanswer: THE CASTLE`
- **Trace:** 17 iterations. The agent **successfully** downloaded the BBC PDF, extracted the text with Python, and found `INT. THE CASTLE - DAY`. It correctly reasoned the answer is "THE CASTLE". But the final output was verbose instead of just the answer.
- **Root cause:** The agent had the exact right answer but didn't output ONLY the answer as instructed.
- **Harness fix:** The answer extraction needs to be more aggressive. When the output contains "answer: X" or the answer is embedded in explanatory text, extract just the answer. The prompt says "call the answer tool with ONLY the final answer" but the agent didn't use the answer tool properly.

### gaia-bda648d7 — Nedoshivina specimens
- **Q:** Where were the Vietnamese specimens described by Kuznetzov in Nedoshivina's 2010 paper eventually deposited? Just give me the city name without abbreviations.
- **Expected:** `Saint Petersburg` | **Got:** `St. Petersburg`
- **Trace:** 5 iterations. Found the archive.org page which states "deposited in the Zoological Institute, St. Petersburg". The agent answered "St. Petersburg".
- **Root cause:** The question explicitly said "without abbreviations" but the agent copied "St. Petersburg" verbatim from the source.
- **Harness fix:** Add a post-processing check for common abbreviations (St. → Saint, Mt. → Mount, etc.) when the question specifies "without abbreviations" or "full name". This is a simple string replacement the harness could do.

### gaia-46719c30 — Pie Menus paper (first paper by author)
- **Q:** Of the authors that worked on the paper "Pie Menus or Linear Menus, Which Is Better?" in 2015, what was the title of the first paper authored by the one that had authored prior papers?
- **Expected:** `Mapping Human Oriented Information to Software Agents for Online Systems Usage`
- **Got:** [Long rambling text with leaked `<tool_call><function=web_search>...` XML]
- **Trace:** 14 iterations. Correctly identified Pietro Murano as the author with prior papers. Found his publications page. But the output contained raw tool-call XML that leaked into the answer instead of being executed.
- **Root cause:** (e) Tool call syntax leaked into output + (a) may have picked wrong "first" paper.
- **Harness fix:** Strip tool-call XML/DSML syntax from final answers. If the output contains `<tool_call>` or `<｜DSML｜>`, it's not a valid answer — the harness should detect this and force a clean answer.

### gaia-cf106601 — 1928 Olympics smallest delegation
- **Q:** What country had the least number of athletes at the 1928 Summer Olympics? Give the IOC country code.
- **Expected:** `CUB` | **Got:** [Rambling text with leaked `<｜DSML｜tool_calls>` XML]
- **Trace:** 17 iterations. The agent made **MANY malformed tool calls** using `{'arguments': {'query': ...}}` format instead of `{'query': ...}`. The harness returned "Missing required argument" errors repeatedly. The agent eventually gave up and output its internal reasoning with DSML XML leaked.
- **Root cause:** (c) Repeated tool format errors + (e) XML leaked into output.
- **Harness fix:** (1) **CRITICAL:** Normalize the `{'arguments': {...}}` wrapper. When the model passes arguments nested under an 'arguments' key, unwrap it automatically. This single fix would have saved this task. (2) Strip DSML/tool-call syntax from final outputs.

### gaia-e142056d — Bob's coin game
- **Q:** [Complex game theory problem about 30 coins in 3 boxes] If Bob uses optimal strategy, what's the minimum amount of money he can win?
- **Expected:** `16000` | **Got:** [Massive reasoning dump, no clear answer]
- **Trace:** 2 iterations, no tools. The agent attempted to solve the game theory problem through pure reasoning. It enumerated valid arrangements and started minimax analysis but got lost in the complexity. The output was its internal reasoning, not a final answer.
- **Root cause:** (a) Analysis paralysis + (e) didn't output a clean answer.
- **Harness fix:** The "forced answer" mechanism should extract a numeric answer from reasoning. If the output contains a number that looks like an answer, use it. Also, for math problems, the harness could suggest using the `calculate` tool or Python.

### gaia-ec09fa32 — Ping-pong balls (PASSED with DeepSeek-Pro)
- **Q:** [Complex probability problem about 100 ping-pong balls on a ramp with pistons] Which ball has highest probability of being ejected?
- **Expected:** `3` | **Got:** [Python simulation code dump]
- **Trace:** 2 iterations, no tools. The agent tried to reason about the problem and wrote a Python simulation, but output the code instead of running it or providing the answer.
- **Root cause:** (a) Wrong approach + (e) output code instead of answer.
- **Harness fix:** Model-specific gap (DeepSeek-Pro got this right). The harness could detect when output contains code blocks and prompt for the actual answer.

---

## Category (a): Wrong Reasoning Despite Having Info — 5 tasks

### gaia-d0633230 — Scikit-learn changelog
- **Q:** In the Scikit-Learn July 2017 changelog, what other predictor base command received a bug fix? Just give the name, not a path.
- **Expected:** `BaseLabelPropagation` | **Got:** `predict_proba`
- **Trace:** 17 iterations. Found the v0.19 changelog (July 2017). Searched for bug fixes related to "predict". Concluded the answer was "predict_proba".
- **Root cause:** Misread the changelog or misinterpreted the question. The question asks for a "predictor base command" (a class like BaseLabelPropagation), but the agent answered with a method name (predict_proba).
- **Harness fix:** This is a reasoning gap. The harness could add a verification: "Does your answer match the expected type? The question asks for a 'predictor base command' (a class name), not a method."

---

## Category (c): Tool Call Format Errors — 3 tasks

**This is the most clear-cut harness fix.**

The model repeatedly used the wrong format:
```python
# WRONG (what the model did):
web_search({'arguments': {'query': '...'}})
web_fetch({'arguments': {'url': '...'}})
run({'arguments': {'command': [...]}})

# CORRECT (what the harness expects):
web_search({'query': '...'})
web_fetch({'url': '...'})
run({'command': [...]})
```

**Affected tasks:**
- gaia-5d0080cb: 4 malformed calls
- gaia-c365c1c7: 2 malformed calls  
- gaia-cf106601: 8 malformed calls (this task was destroyed by this issue)

**Harness fix:** In the tool dispatcher, if the arguments dict has a single key 'arguments' whose value is a dict, unwrap it automatically. This is a 5-line fix that would have saved 3 tasks.

---

## Category (d): Gave Up Too Early — 4 tasks

- **gaia-5d0080cb:** Guessed "0.7" instead of continuing to try PDF extraction.
- **gaia-7673d772:** Output empty string after timeouts.
- **gaia-c365c1c7:** Output empty string after 14 iterations (was on the right track with Wikipedia list).
- **gaia-dc22a632:** Provided best-effort guess with disclaimer after 17 iterations.

**Harness fix:** Empty output should never be accepted. The "forced answer" mechanism needs to be more aggressive. If the agent has any relevant information, it should provide a best-effort answer rather than nothing.

---

## Harness Fixes — Prioritized

### P0 (would have fixed multiple tasks immediately):

1. **Normalize `{'arguments': {...}}` wrapper** (fixes 5d0080cb, c365c1c7, cf106601)
   - In tool dispatcher: if args has single key 'arguments' with dict value, unwrap it.
   - 5-line fix. No downside.

2. **Strip tool-call syntax from final answers** (fixes 46719c30, cf106601)
   - If output contains `<tool_call>`, `<｜DSML｜>`, or similar, strip it and extract the actual answer text.
   - Or reject and force a clean answer.

3. **Never accept empty output** (fixes 7673d772, c365c1c7)
   - The forced-answer mechanism must trigger on empty output.
   - Provide best-effort answer from partial information.

### P1 (would have helped significantly):

4. **Detect search loops** (fixes 305ac316, b415aba4)
   - If N consecutive searches return no new relevant information, force a strategy change.
   - 305ac316 wasted 70k tokens on 16 similar queries. b415aba4 wasted 16 iterations.
   - Heuristic: if the same domains/URLs keep appearing with no new facts, break the loop.

5. **Abbreviation expansion** (fixes bda648d7)
   - When question says "without abbreviations" or "full name", expand common abbreviations: St. → Saint, Mt. → Mount, etc.
   - Simple post-processing step.

6. **Exact string matching from input** (fixes 3cef3a44)
   - When the question provides a list and asks to select from it, verify the output uses exact strings from the input.
   - "fresh basil" was in the input; "basil" was not.

7. **Answer extraction from verbose output** (fixes 4b6bb5f7)
   - When output contains "answer: X" pattern, extract X.
   - When output is explanatory text containing the answer, use the answer tool more aggressively.

### P2 (nice to have):

8. **Prevent hallucination when source inaccessible** (fixes 72e110e7)
   - If the agent cannot retrieve the source data, it should not invent an answer.
   - Better to output "unable to retrieve" than a made-up country.

9. **Type checking for answers** (fixes d0633230)
   - If question asks for a "class" or "command name", verify the answer looks like one.
   - "predict_proba" is a method, not a "predictor base command" (class).

---

## Model-Specific Gaps (not harness-fixable)

Two tasks **passed with DeepSeek-V4-Pro but failed with Qwen**:
- **gaia-a1e91b78** (YouTube bird species): DeepSeek got `3`, Qwen got `31`
- **gaia-ec09fa32** (Ping-pong balls): DeepSeek got `3`, Qwen output code

These are reasoning capability differences, not harness issues.

---

## Token Waste Analysis

| Task | Tokens | Outcome |
|------|--------|---------|
| 305ac316 | 70,274 | 16 iterations of fruitless searches |
| d0633230 | 86,139 | 17 iterations, wrong conclusion |
| cf106601 | 92,293 | 17 iterations, destroyed by tool format errors |
| b415aba4 | 80,037 | 16 iterations of ineffective searches |
| 72e110e7 | 81,467 | 17 iterations, source inaccessible |
| 5d0080cb | 76,137 | 13 iterations, PDF timeout + tool errors |
| 4b6bb5f7 | 149,629 | 17 iterations, **had the right answer** but formatted wrong |

**Total wasted on failures:** ~700k tokens across 16 tasks.

The most egregious: **gaia-4b6bb5f7 used 149k tokens to find the right answer ("THE CASTLE") and then formatted it wrong.** The harness must do better at answer extraction.
