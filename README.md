# CdrStackLimit

Permission-based virtual block stacks for Paper 1.21.11.

CdrStackLimit lets selected ranks hold more than Minecraft's normal per-slot stack size without forcing the client to use an invalid native stack amount. The real amount is stored as server-side item data while the visible vanilla amount remains within Minecraft's normal client-safe limit.

## v0.2.0 — Natural Inventory Interaction

- Everything from v0.1.0
- Safe right-click split for virtual stacks
- Safe left-click manual merge up to the player's rank limit
- Right-click one-by-one placement and merging
- Shift-click between hotbar and main inventory
- Shift-click from supported containers into the player inventory with automatic virtual merging
- Shift-click from the player inventory into supported containers with automatic vanilla unpacking
- Virtual-stack drag support inside the player inventory
- Virtual-stack drag support inside supported storage containers
- Double-click collect up to the configured virtual stack limit
- Q drops one logical item
- Ctrl+Q drops the complete logical stack
- Clicking outside the inventory safely drops logical amounts
- Cursor recovery on disconnect
- Optional next-tick inventory sync for Java/Bedrock consistency
- Supported storage containers are configurable

Virtual stacks still never remain stored inside containers. When moved to a supported container they are expanded back into normal vanilla stacks. This keeps vanilla hoppers and other plugins from reading an invalid hidden stack amount.

## Supported containers by default

```text
CHEST
BARREL
SHULKER_BOX
ENDER_CHEST
HOPPER
DISPENSER
DROPPER
```

Special inventories such as crafting tables, furnaces, anvils, merchants, smithing tables, and similar interfaces stay protected from virtual items.

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

The highest configured permission tier granted to a player wins.

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

A player can then combine eligible blocks into a logical stack up to 1000 blocks in one player inventory slot.

## Safety model

Virtual stacking is intentionally restricted to plain stackable vanilla block items. Items with custom metadata, custom model data, enchantments, lore, or block-state data are not virtualized.

Player inventory slots may contain virtual stacks. Supported containers only receive vanilla stacks, so a logical `STONE x1000` moved into a chest becomes normal vanilla stacks inside the chest. Moving those stacks back to an eligible ranked player merges them into virtual stacks again.

Mixed drag operations that cross both a container and player inventory in the same drag are blocked. This is deliberate anti-dupe behavior.

## Configuration

Default tier example:

```yaml
stack-limits:
  default: 64
  tiers:
    - permission: cdrstacklimit.stack.256
      limit: 256
    - permission: cdrstacklimit.stack.500
      limit: 500
    - permission: cdrstacklimit.stack.1000
      limit: 1000
```

Bedrock/Geyser-friendly inventory refresh:

```yaml
behavior:
  force-inventory-sync: true
```

## Build

```bash
mvn clean package
```

Output:

```text
target/CdrStackLimit-0.2.0.jar
```

## License

MENKIESTES SOFTWARE LICENSE v1.0. See `LICENSE`.
