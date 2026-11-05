# MakeEmSmile

A party game for Android. A friend holds the phone, points the camera at the player, and has
**1 minute to make 'em smile**: faces, fake farts, jokes, anything goes. The player keeps a straight face.

## How a round works

1. **Intro** (for the friend): the rules, an opening joke, and the player's name.
2. **Baseline:** the player relaxes for 3 s. That relaxed face becomes "neutral" for the round.
3. **60 seconds.** Two meters, visible only to the friend:
   - **boring | smile | laugh:** the moment the needle gets into *smile* (held 0.3 s), the player
     has cracked. A slight smile flashes **almost!** first.
   - **reaction:** 😐 nothing · 🫤 skeptical · 🤨 really? · 🙄 yeah, right · 🥱 booring · 🥴 other.
     The current one gets a frame and a little wiggle.
   The clock pauses whenever the face is out of view.
4. **Cracked!** A chime plays, and the app watches one more second to see how hard they cracked.
5. **Results:** the time, the frozen face, an emoji timeline of the round (😐 🙄 🤨 😄💥), and the
   scoreboard. **Next player** passes the phone; **Rematch** keeps the same player; **Share**
   sends a result card.

Only the biggest face in view is tracked, so laughing bystanders don't count.
On phones that can't draw the newest emojis, older look-alikes are used (🫤 → 😕, 🥱 → 😪 …).

## Building

GitHub Actions builds on every push. Open **Actions** → latest run → bottom of the page:

- **MakeEmSmile-test-apk:** install this on your phone to test.
- **MakeEmSmile-play-store-bundle:** the signed `.aab` for Google Play (appears once the signing
  secrets below are added).

### Signing for Google Play (once)

In the repo: **Settings → Secrets and variables → Actions → New repository secret**, add
`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (values are in
`signing-secrets.txt`, kept outside this repo). Never commit the `.jks` key file.

## Privacy

Everything runs on the phone: no uploads, no accounts, no ads, no tracking. See `PRIVACY.md`.

## Tuning

In `Reactions.kt`: `SMILE_LEVEL` (0.30) is where *smile* starts = cracked; `LAUGH_LEVEL` (0.65) is
where *laugh* starts. Each reaction has its own trigger level in `update()`.
