# Third-Party Notices

This file credits third-party work that influenced or was ported into this project's own code, per each work's license terms.

## HHSmithy/PokerHandHistoryParser

`backend/src/main/java/com/pokeranalytics/importing/` (the `model` and `parser` packages, including their per-room subpackages) is a Java re-implementation based on the parsing behavior and sample hand-history test fixtures of:

- <https://github.com/HHSmithy/PokerHandHistoryParser> (a fork of the original <https://github.com/KBelbina/PokerHandHistoryParser>)

See `docs/decisions/ADR-005-early-import-parser.md` for why and how this was done: our code is a fresh Java implementation (regex/state-machine style, not a line-by-line translation), but the parsing behavior, edge cases, and several sample hand-history fixtures under `backend/src/test/resources/hand-history/` are directly based on that project, including its "Pacific" (888/Cassava/LuckyAcePoker.com) sample fixtures ported into `backend/src/test/resources/hand-history/poker888/`.

Original license (The MIT License):

```
Copyright (c) 2014 "Luke" Kader Belbina

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
