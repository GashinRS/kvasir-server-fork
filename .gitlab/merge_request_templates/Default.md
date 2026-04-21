## Summary

<!-- What does this MR do? Keep it brief — the MR title should follow
     Conventional Commits (feat:, fix:, refactor:, etc.) and will become
     the squash-commit message on main. -->

<!-- BREAKING CHANGES
     If this MR introduces a breaking change:

     1. Use `!` in the MR title, e.g. `feat!: redesign auth API`
     2. Add a BREAKING CHANGE: description in the body of the MR, e.g.
        If the title already tells the full story, the footer is optional.
     4. The MR description gets added to the squash commit message on main,
        Releasaurus then picks up the BREAKING CHANGE: description and adds it to the release notes.
        If you don't squash commit and descriptions are warranted, make sure to add
        BREAKING CHANGE: descriptions to the individual commits as well.
-->
<!-- BREAKING CHANGE: Uncomment and edit in case a description is warranted -->

## Related Issues

<!-- Closes #123, Relates to #456 -->

## Merge Checklist

- [ ] MR title follows [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, etc.)
- [ ] Breaking changes: title uses `!` and a `BREAKING CHANGE:` footer is included above when the title alone doesn't tell the full story
- [ ] Documentation updated (if applicable)

<!-- MERGE STRATEGY
     Default: squash merge — the MR title becomes the commit on main.

     Use a regular merge (no squash) when this branch bundles multiple
     intentional commits (e.g. several feat/fix branches merged into one
     dev branch). In that case, ensure every individual commit follows
     Conventional Commits.
-->
