# TODO

- [ ] Investigate GitHub sign-in failing on the Lenovo tablet with `Unable to resolve host "github.com": No address associated with hostname`. Network permissions are included, but the user still cannot connect after the suggested checks. Deferred at the user’s request; do not treat GitHub backup as verified on that tablet. Capture device network/DNS diagnostics and exercise device authorization on the tablet when resumed.
- [ ] Verify 0.4.1 highlighter and first-stroke latency on the Galaxy Tab S6 Lite. User reports lag and first-writing delay in 0.4.0; native/surface warmup and incremental highlighter rendering are implemented, but emulator results cannot certify S Pen latency. Compare cold/warm opening of empty and large PDF notes.
- [ ] Diagnose Dotnote widgets missing from the full widget list on the Galaxy Tab S6 Lite with 0.4.0. Both providers are present in the packaged APK; Samsung launcher discovery remains unverified.
