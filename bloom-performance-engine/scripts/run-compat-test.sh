#!/usr/bin/env bash
# Runs one in-game compatibility test: copies the given mod jars into the dev
# run's mods folder, runs the benchmark with the validator on (1 section in 4),
# collects the compatibility report, the benchmark JSON and the F3 screenshot,
# then removes the mods again. The world is a fresh copy; the original is
# never touched.
#
#   scripts/run-compat-test.sh <label> <backend: opengl|vulkan> <jar>...
set -euo pipefail
label=$1; backend=$2; shift 2
root="$(cd "$(dirname "$0")/.." && pwd)"
java="$root/java"
out="$root/benchmarks/compat/$label"
rm -rf "$out"
mkdir -p "$out" "$java/run/mods"
if compgen -G "$java/run/mods/*.jar" > /dev/null; then
	echo "run/mods is not empty: refusing to mix test mods with existing ones" >&2
	exit 1
fi
cp "$@" "$java/run/mods/"
options="$java/run/options.txt"
cp "$options" "$options.compat-backup"
sed -i "s/^preferredGraphicsBackend:.*/preferredGraphicsBackend:\"$backend\"/" "$options"
cleanup() {
	rm -f "$java"/run/mods/*.jar
	mv "$options.compat-backup" "$options"
}
trap cleanup EXIT
rm -rf "$java/run/saves/bloom-bench" "$java/run/.bloom/validation-failure.json"
cp -r "$java/run/saves/New World" "$java/run/saves/bloom-bench"
export GRADLE_USER_HOME="D:\\bloom-build\\gradle-home"
(cd "$java" && timeout 600 ./gradlew.bat runBenchmark --no-daemon -q -Pbench.world=bloom-bench -Pbench.engine=true -Pbench.validate=${VALIDATE:-4} \
	-Pbench.duration=10 ${EXTRA_ARGS:-} > "$out/game.log" 2>&1) || echo "game exited with $?" >&2
cp "$java/run/.bloom/compatibility-report.json" "$out/" 2>/dev/null || true
cp "$java/run/.bloom/validation-failure.json" "$out/" 2>/dev/null || true
latest=$(ls -t "$java/run/.bloom/benchmarks/" | head -1)
cp "$java/run/.bloom/benchmarks/$latest" "$out/benchmark.json"
shot=$(ls -t "$java/run/screenshots/" 2>/dev/null | head -1)
[ -n "$shot" ] && cp "$java/run/screenshots/$shot" "$out/f3.png"
echo "$out"
