# Blood Arts (Fabric 1.21.1)
Item: Blood Focus (Combat tab, or /give @s bloodarts:blood_focus)
Sneak + right click = switch technique. Right click = use it. Each has its own cooldown and costs your health
(you can't use one if it would kill you; creative ignores costs).

| Technique      | Cost  | Cooldown | Effect |
|----------------|-------|----------|--------|
| Piercing Shot  | 1 heart   | 0.6s | 32-block beam, 10 dmg |
| Blood Slash    | 1.5 hearts| 1.5s | 120-degree arc, 5.5 blocks, 8 dmg to all hit |
| Crimson Bind   | 1 heart   | 3s   | Target gets Slowness V, Weakness II, Glowing for 5s |
| Blood Drain    | 0.5 heart | 3s   | 6 dmg to target within 8 blocks, heals you 2 hearts |
| Crimson Dash   | 1 heart   | 2.5s | Lunge forward, 6 dmg to anything in your path |
| Blood Armor    | 1 heart   | 10s  | Absorption II + Resistance I for 15s |
| Blood Boost    | 2 hearts  | 5s   | Strength II + Speed II for 10s |
| Blood Nova     | 3 hearts  | 15s  | 6-block burst, 12 dmg + knockback to everything nearby |

## Build
1. Copy `gradle/`, `gradlew`, `gradlew.bat` from https://github.com/FabricMC/fabric-example-mod into this folder
   (or run `gradle wrapper --gradle-version 8.10`).
2. `./gradlew build` -> jar in build/libs/
3. Put the jar in .minecraft/mods with Fabric Loader + Fabric API for 1.21.1.
