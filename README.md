# CdrStackLimit

Permission-based virtual block stacks for Paper 1.21.11.

CdrStackLimit lets selected ranks hold more than Minecraft's normal per-slot stack size without forcing the client to use an invalid native stack amount. The plugin stores the real amount as server-side item data and keeps the visible vanilla amount within Minecraft's normal limit.

## v0.1.0

- Rank/permission-based stack limits
- Default tiers: 256, 500, and 1000
- Automatic pickup + merge for eligible block items
- Block placement decrements the virtual amount correctly
- Safe Q / Ctrl+Q drop handling
- Death drops are expanded back to normal vanilla stacks
- Periodic reconciliation after rank/permission changes
- Actionbar + lore display for the real amount
- Container/crafting transfer protection
- Anti-dupe protections for drag, split, shift-click, double-click, and manual same-item merging
- No client mod required; designed to remain usable for Java and Bedrock/Geyser players

## Requirements

- Paper 1.21.11
- Java 21

LuckPerms is optional. Any permission plugin can grant the tier permissions.

## Permissions

```text
cdrstacklimit.stack.256
cdrstacklimit.stack.500
cdrstacklimit.stack.1000
cdrstacklimit.admin
```

The highest granted configured tier wins.

## Commands

```text
/cdrstacklimit info
/cdrstacklimit reload
/cdrstacklimit normalize [player]
```

Aliases: `/cdrstack`, `/stacklimit`.

## Example

Give a rank a 1000 block limit:

```text
/lp group lunar permission set cdrstacklimit.stack.1000 true
```

A player picking up Stone while already carrying Stone can then build a virtual stack up to 1000 logical blocks in one inventory slot.

## Important v0.1.0 safety rules

Virtual stacking is intentionally restricted to plain stackable block items. Items with custom metadata, custom model data, enchantments, lore, or block-state data are not virtualized.

Virtual stacks cannot be inserted into chests, shulkers, furnaces, crafting grids, or other non-player inventories in this release. This prevents vanilla container and hopper logic from truncating or duplicating the hidden amount.

Use normal left-click to move or swap a virtual stack inside the player inventory. Manual splitting, dragging, shift-clicking, double-click collection, and manual merging with another stack are blocked in v0.1.0. Pickup auto-merge remains supported.

## Build

```bash
mvn clean package
```

Output:

```text
target/CdrStackLimit-0.1.0.jar
```

## License

MENKIESTES SOFTWARE LICENSE v1.0. See `LICENSE`.
