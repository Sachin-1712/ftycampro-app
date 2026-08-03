# research

Everything learned about the camera, and the strict separation between what is
known and what is guessed.

```
research/
├── 00-device-facts.md        ground truth about the hardware and network
├── 01-apk-analysis/          static analysis of the vendor APK
├── 02-network-capture/       capture logs and per-scenario notes
├── 03-protocol-hypotheses.md guesses, with confidence and disconfirming tests
├── findings/                 confirmed facts, one file each
└── captures/                 pcaps and APKs (git-ignored)
```

## The one rule

**`findings/` holds only things that have been demonstrated.** A finding names the
evidence and the command that reproduces it. If you believe something but haven't
shown it, it goes in `03-protocol-hypotheses.md` with a confidence level and — the
part that's easy to skip and matters most — the observation that would prove it
wrong.

The reason for the split is that this project starts from a conclusion that was
reached too early. "All ports closed, therefore proprietary P2P" skipped over the
fact that 32,249 of those ports answered with a TCP RST, which says the device is
reachable and its stack is alive. Same conclusion, quite possibly, but for a reason
that can be checked. Keeping guesses out of `findings/` is what stops that from
happening twice.

## Writing a finding

Copy `findings/_TEMPLATE.md`, name it `NN-short-slug.md`, fill it in. Keep them
small and single-purpose — one claim per file, so a finding can be individually
retracted when it turns out to be wrong.

## Captures

`captures/` is git-ignored. Capture files contain the device UID, session tokens,
video, and — in provisioning captures — your Wi-Fi passphrase, which the SoftAP
setup exchange transmits in a form that is often barely obfuscated. Commit redacted
excerpts into a finding rather than the raw file.
