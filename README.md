> **Disclaimer**: This plugin is not suitable for production use as it was created using AI assistance. It is currently running on https://Anarchadia.org for testing purposes.

# NoPalette

NoPalette is a lightweight Paper plugin designed to patch the "Palette Exploit", a chunk analysis method used by Minecraft client modifications. This exploit allows players to determine if a chunk has been previously loaded by another player. This enables "chunk tracing," where a player can follow a trail of loaded chunks to find another player's base or track their movements across the world.

## The Palette Exploit

On modern Minecraft versions (1.18+), chunk data is sent to the client using **paletted containers**. This is an optimization where a list of unique block types in a chunk section (the "palette") is created, and then each block in that section is represented by a short index into that palette.

The exploit works by observing a subtle difference between the data for a chunk that is being loaded for the very first time versus a chunk that has been loaded before:

-   **First-Time-Load Chunks**: When a chunk is generated and sent to a player for the first time, the server often optimizes the palette by placing the most common block (usually `minecraft:air`) at the very first position (index 0).
-   **Previously-Loaded Chunks**: If a chunk has been loaded before, modified, or saved and re-loaded by the server, this palette optimization is not always reapplied in the same way. The block at index 0 might be `minecraft:stone` or another block type.

Cheat clients intercept and analyze the chunk data packets (`ChunkDataS2CPacket`). By checking if `minecraft:air` is at index 0 of the block palette for a majority of the chunk's sections, they can make a highly accurate guess that the chunk is "new" (i.e., never loaded by another player before). This allows them to build a map of player activity, creating a "tracer" that leads directly to other players' bases.

## How NoPalette Patches the Exploit

NoPalette uses **ProtocolLib** to intercept outgoing `MAP_CHUNK` packets before they reach the client. It implements a performance-optimized patching strategy:

### 1. **Fast Pre-Check (Read-Only Scan)**
   - Parses the raw chunk data buffer (ByteBuffer) without allocation.
   - Skips non-paletted sections (global palette: >8 bits; single value: 0 bits).
   - For paletted sections (1-8 bits), reads the palette size and checks if index 0 is AIR (state ID 0).
   - If no vulnerable palettes found, the packet is forwarded unchanged—minimal overhead for safe chunks.

### 2. **Full Patching (If Vulnerable)**
   - Only processes chunks where AIR is at index 0 in a multi-entry palette (>1 entry).
   - **Swaps AIR with the next entry** (index 1, typically a common block like stone or water).
   - **Remaps the data array**: Scans the bit-packed longs, swapping indices (0 ↔ 1) where needed, using bit manipulation for efficiency (no full decompression).
   - Biomes are skipped (no exploit there), but the full buffer is reconstructed to preserve integrity.
   - Updated buffer replaces the original via reflection on the packet's data field.

This simple swap makes all chunks appear to have been previously loaded, effectively neutralizing the chunk tracing exploit.

### Key Features
- **Version Support**: Optimized for 1.21.5+ chunk formats (ClientboundLevelChunkPacketData).
- **Fallbacks**: Handles legacy formats (pre-1.19.4) and errors gracefully (e.g., reverts to original on failure).
- **Performance**: Pre-check exits early for ~99% of packets; full patch only on vulnerable ones.
- **Debug Mode**: Enable via `config.yml` to log detections and patches for troubleshooting.

## Installation

1.  **Prerequisites**:
    *   A PaperMC/Spigot server (1.18+).
    *   [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) installed.

2.  **Build and Install**:
    *   Clone the repository and build with Maven: `mvn clean package`.
    *   Place `NoPalette-1.0.jar` in your server's `plugins/` folder.
    *   Restart the server. Check console for "PaletteExploitPatcher initialized."

3.  **Verify**:
    *   Run `/plugins` to confirm NoPalette is loaded.
    *   If ProtocolLib is missing, the patcher will not load.

## Configuration

Edit `plugins/NoPalette/config.yml` (auto-generated on first run):

```yaml
patches:
  palette-exploit:
    debug: false  # Set to true for verbose logging of detections and patches
```

## Credits

-   Original exploit analysis from client mods like Trouser-Streak.
-   ProtocolLib by dmulloy2 for packet interception.
-   Developed for Anarchadia servers.
