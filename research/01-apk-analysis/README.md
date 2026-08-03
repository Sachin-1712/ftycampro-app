# APK analysis

Static analysis of the vendor app. Output of `tools/apk_analyze.sh` lands here;
`jadx-out/`, `apktool-out/` and `unzipped/` are git-ignored as large, regenerable
and vendor-copyrighted.

## Subject

| Field | Value |
|---|---|
| Package name | |
| Version name / code | |
| SHA-256 of the APK | |
| Pulled from | own device, `adb pull` |
| Date | |

Pull the APK from your own phone rather than a mirror, so it matches the build you
capture traffic from.

## Run

```bash
bash tools/apk_analyze.sh research/captures/<pkg>-base.apk
python tools/apk_signatures.py research/01-apk-analysis --json signature-report.json
```

## Results

### SDK identification

Top-scoring family, and the evidence that got it there:

### Native libraries

| Library | Size | Exported symbols of interest |
|---|---|---|

Dump exports with `nm -D --defined-only <lib.so>`. Names like `PPPP_Connect`,
`IOTC_Connect_ByUID` or `avClientStart2` identify the SDK outright.

### Endpoints

Hostnames and IPs found in strings. Mark each as **confirmed** once it also shows
up as a destination in a capture — agreement between a static string and observed
traffic is what turns a candidate into a fact.

| Host / IP | Occurrences | Seen in traffic? | Role |
|---|---|---|---|

### Authentication

How the app identifies the device and itself. Where does the UID come from? Is
there an account login, and is it required for the local path or only the remote
one? What is sent on first pairing?

### Encryption

Not "does it encrypt" but **where the key comes from**. A fixed key compiled into
the app is the common case in this hardware class and changes the whole outlook.

| Location | Algorithm | Key source |
|---|---|---|

### Codecs

| Codec | Evidence |
|---|---|

Determines what Media3 needs to be handed, and whether an FFmpeg extension is
needed at all.

### Ads and tracking

Inventory these — removing them is the point of the project, and knowing which SDKs
are present tells you which traffic in the captures is noise.

## Manual reading list

Once the signature report has narrowed things down, these are usually worth opening
by hand:

- the class that owns the UID / device list
- whatever calls into the native library — the JNI boundary is the protocol boundary
- the `network_security_config.xml` (decides whether mitmproxy is even viable)
- any class with `P2P`, `Connect`, `Session` or `Stream` in its name
