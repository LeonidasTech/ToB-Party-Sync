# ToB Party Sync

Automatically manages Party groups when joining a Theatre of Blood team.

## How It Works

When you join a ToB team, the plugin creates a party group using the format `[World][TeamLeader]`.

Examples:
- World 619, Team Leader "WISEOLDMAN" → `619WISEOLDMAN`
- World 330, Team Leader "JOHNCENA" → `330JOHNCENA`

The plugin monitors team changes and automatically switches party groups when the team leader changes.

## Configuration

**Auto leave when exiting ToB** (Default: Enabled)
- Automatically leaves the party group when you exit Theatre of Blood

**Enable chat messages** (Default: Enabled)  
- Shows in-game chat notifications when joining/leaving party groups

**Force join mode** (Default: Enabled)
- When enabled: Always joins raid party groups, even if already in another party
- When disabled: Only joins if not already in a non-sync party group

## License

BSD 2-Clause License
