/* tslint:disable */
/* eslint-disable */

/**
 * Parent page → camera, used for mouse/touch look. `touch` selects the higher
 * mobile sensitivity. Ignored for rotation once Java reports its pose.
 */
export function apply_look(dx: number, dy: number, touch: boolean): void;

/**
 * Returns the camera to the standalone free-fly debug pose.
 */
export function clear_java_player(): void;

export function clear_registered_materials(): void;

export function clear_time_of_day(): void;

/**
 * Advances the camera by one frame.
 * Debug helper for the headless harness: current eye pose as text.
 */
export function debug_pose(): string;

/**
 * Debug helper for the headless harness: ASCII biome/height map around spawn.
 */
export function debug_world_map(): string;

export function post_enabled(): boolean;

/**
 * Exact bridge name the JavaScript glue calls for a 16x16x16 chunk slice:
 *
 * ```js
 * body.receive_java_chunk(chunkX, chunkZ, int32Array4096);
 * ```
 *
 * The first real chunk retires the synthetic demo world. `cy` is implicitly 0;
 * worlds with a non-zero minimum Y use `receive_java_section` instead.
 */
export function receive_java_chunk(chunk_x: number, chunk_z: number, block_ids: Int32Array): boolean;

/**
 * Java → Body packed skylight/blocklight for one section.
 *
 * Java can obtain the exact vanilla values with
 * `Level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(pos)`
 * (and the same for `BLOCK`), packing each nibble as `sky << 4 | block`.
 */
export function receive_java_light(cx: number, cy: number, cz: number, packed: Uint8Array): boolean;

/**
 * Section-level form used by the full Java capture hook (16³ IDs plus cy).
 */
export function receive_java_section(cx: number, cy: number, cz: number, block_ids: Int32Array): boolean;

export function render_distance(): number;

export function reset_world(): void;

/**
 * Entry point called by the harness page after `init()` resolved.
 */
export function run(): void;

/**
 * Register a material using explicit array layers (`-1` = no texture).
 * The page/Java can use this when it has already resolved model textures.
 */
export function set_block_material(block: number, top: number, bottom: number, side: number, cutout: boolean): void;

/**
 * Register a real block-state ID by its registry name (`minecraft:dirt`).
 * Called from the Java palette handshake in `bridge.js`.
 */
export function set_block_name(block: number, name: string): void;

/**
 * The hybrid page owns input forwarding (bridge -> SDL/Java), so disable the
 * renderer's duplicate DOM listeners on its visible canvas.
 */
export function set_host_input(hosted: boolean): void;

/**
 * Java Brain → Body player pose. `y` is the eye height (`LocalPlayer.getEyeY`),
 * so the Rust camera follows Java's collision/gravity result exactly.
 */
export function set_java_player(x: number, y: number, z: number): void;

/**
 * Java Brain → Body rotation, in Minecraft degrees (`getYRot` / `getXRot`).
 * The renderer converts to its own camera convention in `controls`.
 */
export function set_java_rotation(yaw_degrees: number, pitch_degrees: number): void;

export function set_post_enabled(on: boolean): void;

/**
 * Chunk (16 m) view distance. Clamped so a bad host value cannot turn the
 * fog and the cull radius into nonsense.
 */
export function set_render_distance(chunks: number): void;

/**
 * Sets the sky/sun phase from an external clock. `fraction` is one full day
 * (0 = sunrise, 0.25 = noon, 0.5 = sunset, 0.75 = midnight).
 */
export function set_time_of_day(fraction: number): void;

export function set_virtual_fly(on: boolean): void;

/**
 * Renderer-only input fallback used by `bridge.js` when no Java iframe/SDL
 * shim is present (the deployed `old_mcweb` page). Works regardless of
 * `set_host_input`, so the hybrid page can keep its DOM listeners disabled.
 */
export function set_virtual_key(code: string, down: boolean): void;

export function set_virtual_stick(x: number, y: number): void;

/**
 * Number of layers the page must upload.
 */
export function texture_count(): number;

/**
 * Resolve a vanilla asset path (`assets/minecraft/textures/block/dirt.png`) to
 * its array layer. Returns -1 when the Body does not carry that texture.
 * Java/JS use this when they send a material table for real block-state IDs.
 */
export function texture_layer(path: string): number;

/**
 * Asset path of a layer, so the page can find it in the resource archive.
 */
export function texture_name(layer: number): string;

export function upload_section(cx: number, cy: number, cz: number, version: number, ids: Int32Array): boolean;

/**
 * Bridge for the page: hand over one PNG from the client jar.
 */
export function upload_texture(layer: number, png: Uint8Array): void;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly memory: WebAssembly.Memory;
    readonly apply_look: (a: number, b: number, c: number) => void;
    readonly clear_java_player: () => void;
    readonly clear_registered_materials: () => void;
    readonly clear_time_of_day: () => void;
    readonly debug_pose: (a: number) => void;
    readonly debug_world_map: (a: number) => void;
    readonly post_enabled: () => number;
    readonly receive_java_chunk: (a: number, b: number, c: number, d: number) => number;
    readonly receive_java_light: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly receive_java_section: (a: number, b: number, c: number, d: number, e: number) => number;
    readonly render_distance: () => number;
    readonly reset_world: () => void;
    readonly run: () => void;
    readonly set_block_material: (a: number, b: number, c: number, d: number, e: number) => void;
    readonly set_block_name: (a: number, b: number, c: number) => void;
    readonly set_host_input: (a: number) => void;
    readonly set_java_player: (a: number, b: number, c: number) => void;
    readonly set_java_rotation: (a: number, b: number) => void;
    readonly set_post_enabled: (a: number) => void;
    readonly set_render_distance: (a: number) => void;
    readonly set_time_of_day: (a: number) => void;
    readonly set_virtual_fly: (a: number) => void;
    readonly set_virtual_key: (a: number, b: number, c: number) => void;
    readonly set_virtual_stick: (a: number, b: number) => void;
    readonly texture_count: () => number;
    readonly texture_layer: (a: number, b: number) => number;
    readonly texture_name: (a: number, b: number) => void;
    readonly upload_section: (a: number, b: number, c: number, d: number, e: number, f: number) => number;
    readonly upload_texture: (a: number, b: number, c: number) => void;
    readonly __wasm_bindgen_func_elem_1746: (a: number, b: number, c: number, d: number) => void;
    readonly __wasm_bindgen_func_elem_594: (a: number, b: number, c: number, d: number) => void;
    readonly __wasm_bindgen_func_elem_594_35: (a: number, b: number, c: number, d: number) => void;
    readonly __wasm_bindgen_func_elem_594_36: (a: number, b: number, c: number, d: number) => void;
    readonly __wasm_bindgen_func_elem_345: (a: number, b: number, c: number) => void;
    readonly __wasm_bindgen_func_elem_345_32: (a: number, b: number, c: number) => void;
    readonly __wasm_bindgen_func_elem_345_33: (a: number, b: number, c: number) => void;
    readonly __wasm_bindgen_func_elem_345_34: (a: number, b: number, c: number) => void;
    readonly __wasm_bindgen_func_elem_350: (a: number, b: number) => void;
    readonly __wbindgen_export: (a: number, b: number) => number;
    readonly __wbindgen_export2: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_export3: (a: number) => void;
    readonly __wbindgen_export4: (a: number, b: number, c: number) => void;
    readonly __wbindgen_export5: (a: number, b: number) => void;
    readonly __wbindgen_add_to_stack_pointer: (a: number) => number;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput }} module - Passing `SyncInitInput` directly is deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput } | SyncInitInput): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput> }} module_or_path - Passing `InitInput` directly is deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput> } | InitInput | Promise<InitInput>): Promise<InitOutput>;
