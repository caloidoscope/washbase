// Combine every open pull request into one throwaway local branch to manually test all
// in-progress Features together. The branch is local only and must never be pushed
// (a plain `git push` fails on it; don't push it explicitly either).
//
//   pnpm try:open-prs        then: pnpm install && pnpm dev:all (and pnpm dev:mobile)
//   git switch main          when done (the preview branch is rebuilt on every run)
import { execFileSync } from "node:child_process";

const BRANCH = "preview/all-open-prs";
const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { encoding: "utf8", ...opts }).trim();
const tryRun = (cmd, args) => {
  try {
    run(cmd, args, { stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
};

if (run("git", ["status", "--porcelain"])) {
  console.error("You have uncommitted changes. Commit or stash them first.");
  process.exit(1);
}

const prs = JSON.parse(
  run("gh", ["pr", "list", "--state", "open", "--json", "number,title,headRefName", "--limit", "100"]),
).sort((a, b) => a.number - b.number);
if (prs.length === 0) {
  console.log("No open pull requests. Nothing to combine.");
  process.exit(0);
}

run("git", ["fetch", "--quiet", "origin"]);
run("git", ["switch", "--quiet", "-C", BRANCH, "origin/main"]);
// Safety net: a plain `git push` on this branch fails (an explicit `git push origin <branch>` would not).
run("git", ["config", `branch.${BRANCH}.pushRemote`, "do-not-push"]);

const merged = [];
const skipped = [];
for (const pr of prs) {
  if (tryRun("git", ["merge", "--no-edit", "--quiet", `origin/${pr.headRefName}`])) {
    merged.push(pr);
  } else {
    tryRun("git", ["merge", "--abort"]);
    skipped.push(pr);
  }
}

console.log(`\nOn local branch ${BRANCH} = main + these open PRs:`);
for (const pr of merged) console.log(`  #${pr.number}  ${pr.title}`);
if (skipped.length) {
  console.log("\nSkipped because they conflict with the PRs above (test them on their own with `gh pr checkout <n>`):");
  for (const pr of skipped) console.log(`  #${pr.number}  ${pr.title}`);
}
console.log("\nNext: pnpm install && pnpm dev:all   (mobile: pnpm dev:mobile in a second terminal)");
console.log("When done: git switch main\n");
