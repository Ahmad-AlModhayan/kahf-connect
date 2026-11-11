# Contributing to Kahf Connect

Thanks for helping. Kahf Connect is open source under [Apache-2.0](LICENSE). Please read
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) first, and report security problems through [SECURITY.md](SECURITY.md),
not as public issues.

## Where to start

- The contract is [SPEC.md](SPEC.md); the reasons behind it are in [docs/DESIGN.md](docs/DESIGN.md).
- Open an issue before a large change, so we can agree on the shape first. Small fixes can go straight to a pull
  request.
- The fixtures in [fixtures/](fixtures) are the conformance suite. A change to behaviour starts with a fixture, and
  every SDK must pass it.

## Build and run the checks

Linux, macOS and Windows all work. You need Python 3 with `jsonschema`, the Dart SDK (3.3 or newer) and JDK 17.

```sh
pip install jsonschema
python tools/check_schemas.py          # schemas agree with the fixtures
cd dart && dart test                   # Dart SDK, including the conformance suite
cd kotlin && ./gradlew check           # Kotlin SDK, the same conformance suite (JVM + Android)
```

End to end against the reference connector:

```sh
cd reference/python && python server.py                               # http://127.0.0.1:8765/mcp
cd dart && KAHF_REFERENCE_URL=http://127.0.0.1:8765/mcp dart test
```

Pull requests run the DCO check on every change, and the Kotlin check when Kotlin, fixtures or catalog files change (GitHub Actions, Linux). Run the Dart and schema checks locally before you push.

## Licence of contributions, and your copyright

By submitting a contribution you license it under Apache-2.0, the same licence as the project (Apache-2.0 §5,
"inbound = outbound"). Nothing more is asked of you.

**You keep your copyright.** §5 does not assign copyright to Kahf Labs and grants Kahf Labs no rights beyond what
Apache-2.0 already gives every user of the project. There is no contributor licence agreement for this repository.

## Sign off every commit (DCO)

Every commit needs a `Signed-off-by` line, which certifies the [Developer Certificate of Origin](https://developercertificate.org/)
1.1: that you wrote the change or have the right to submit it under this licence.

```sh
git commit -s -m "Fix the busy-time merge"
```

This adds `Signed-off-by: Your Name <you@example.com>`, using your `git config user.name` and `user.email`. Forgot
one? Fix the last commit with `git commit --amend -s --no-edit`, or a whole branch with
`git rebase --signoff origin/main`, then `git push --force-with-lease` to your own branch.

A GitHub Action (`.github/workflows/dco.yml`) checks every commit in a pull request and fails if a sign-off is
missing. Merge commits are skipped. Commits made in the GitHub web editor or by bots need a sign-off added the same way.

## Pull requests

- One change per pull request, with a description of what and why. Link the issue it closes.
- Keep the checks above green, and add a fixture or test for new behaviour.
- Connectors never write: do not add a path that changes data without the person accepting it (see SPEC.md).
- Do not commit keys or secrets. `fixtures/catalog/test-only.key` is a test key and signs nothing real.
- The names Kahf and Kahf Connect are not licensed for use by forks (see the README).
