# Contributing

I kindly ask anyone who wants to contribute to this project to follow some basic guidelines:

1. Open a discussion around the change before contributing any code

   https://github.com/sventorben/kommons/discussions/categories/ideas)
2. Create a GitHub Issue with a good description associated with the PR
4. One feature/change per PR
5. One commit per PR
6. PR rebased on main (`git rebase`, not `git pull`)
7. Good descriptive [conventional commit message](https://www.conventionalcommits.org/en/v1.0.0/), with link to issue
8. No changes to code not directly related to your PR
9. Includes basic tests

   Please do not add additional test frameworks without prior consultation
10. Include some documentation/extend the README
11. PR must pass DCO check

## Using AI Assistants

Using an AI assistant to write, refactor or document code for this project is absolutely fine. I use them myself.
There is no need to declare which tools you used, and a pull request will never be rejected for having been written
with AI help.

What matters is this: **you are the author, and you own every line you submit.** The assistant is not a contributor,
it is a tool you are responsible for. Concretely, before you open a pull request:

- **Understand the code.** You should be able to explain what every line does and why it is there. If a reviewer asks
  "why this approach?" and the honest answer is "that is what the model produced", it is not ready.
- **Verify it against reality.** Language models invent APIs, configuration keys and behaviour that look entirely
  plausible. Check the code against the actual Keycloak version this project targets — read the sources, run it, or
  both. "It compiled" is not verification.
- **Test it, and mean it.** Tests that assert whatever the implementation happens to do are worse than no tests. A
  test should fail when the behaviour is wrong.
- **Make the documentation true.** Documentation describing behaviour the code does not have is actively harmful, and
  it is a very common failure mode of generated changes.
- **Review it as your own work.** Read the whole diff before you push it, not just the parts you typed.

Put plainly: I am happy to review code you produced with an AI. I am not willing to review code that nobody has
reviewed yet. Sending a large generated change you have not read, understood and checked shifts that work onto me,
and I will close it and ask you to come back once you have.

This is also exactly what signing off your work means, see below: the DCO is you certifying that you have the right
to submit this contribution and that you stand behind it.

## Sign off Your Work

The Developer Certificate of Origin (DCO) is a lightweight way for contributors to certify that they wrote or otherwise have the right to submit the code they are contributing to the project. Here is the full text of the [DCO](http://developercertificate.org/). Contributors must sign-off that they adhere to these requirements by adding a `Signed-off-by` line to commit messages.

```text
This is my commit message

Signed-off-by: Random J Developer <random@developer.example.org>
```

See `git help commit`:

```text
-s, --signoff
    Add Signed-off-by line by the committer at the end of the commit log
    message. The meaning of a signoff depends on the project, but it typically
    certifies that committer has the rights to submit this work under the same
    license and agrees to a Developer Certificate of Origin (see
    http://developercertificate.org/ for more information).
```

## Commit Signing Requirement
All commits to the `main` branch must be signed with a GitHub verified signature. This ensures authenticity and traceability of contributions.

Please follow GitHub’s official documentation on how to configure commit signing: [Signing commits](https://docs.github.com/en/authentication/managing-commit-signature-verification/signing-commits)

To verify that your commits are signed, you can run the following command:

```sh
git log --show-signature
```
