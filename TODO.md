# TODO

- [ ] Confirm 0.5.2 GitHub sign-in on the Lenovo tablet: approve in the browser, return to Dotnote, and verify account/repository selection. Foreground-gated polling and automatic DNS/connection retries are implemented and regression-tested. If hostname resolution still fails while Dotnote is open, collect device network/DNS diagnostics. Live private-repository backup/restore remains unverified.
- [ ] Verify 0.4.1 highlighter and first-stroke latency on the Galaxy Tab S6 Lite. User reports lag and first-writing delay in 0.4.0; native/surface warmup and incremental highlighter rendering are implemented, but emulator results cannot certify S Pen latency. Compare cold/warm opening of empty and large PDF notes.
- [ ] Diagnose Dotnote widgets missing from the full widget list on the Galaxy Tab S6 Lite with 0.4.0. Both providers are present in the packaged APK; Samsung launcher discovery remains unverified.
