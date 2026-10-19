/**
 * The agent's face. Muse gives its agent a plush doll; nanoMuse has its dragon — five stills,
 * one per mood (public/avatars/dragon-*.webp) and four short clips that move them (idle,
 * working, waiting, happy — dragon-*.mp4, the same clips the Android app plays) — or a face the
 * person had drawn for them in the avatar studio (served from the runtime's files, stills and,
 * where the video model made them, clips), or an emoji on a colour for anyone who prefers it.
 * `profile.avatar` names the face; "" means the emoji.
 */

import { fileUrl } from "./api";

/** The dragon: the default face. */
export const DRAGON = "dragon";
export type DragonMood = "idle" | "working" | "waiting" | "happy" | "error";

/**
 * Faces from before 0.1.23 — the red panda drawn live and the six knitted dolls — are gone;
 * a profile still naming one wears the dragon.
 */
const RETIRED = new Set(["panda", "sunny", "moss", "sky", "fox", "bolt", "plum"]);

export function isDragon(id: string | undefined | null): boolean {
  return !id || id === DRAGON || RETIRED.has(id);
}

/**
 * A face from the avatar studio: `profile.avatar` is its id, the stills live in the workspace
 * under `avatar/<id>/` (idle · working · waiting · happy · error, webp) and come through the
 * files API — the same five moods the dragon has.
 */
export function studioUrl(id: string, mood: string): string {
  const still: DragonMood = mood === "working" || mood === "waiting" || mood === "happy" || mood === "error" ? mood : "idle";
  return fileUrl(`avatar/${id}/${still}.webp`);
}

export function dragonUrl(mood: string): string {
  const still: DragonMood = mood === "working" || mood === "waiting" || mood === "happy" || mood === "error" ? mood : "idle";
  return `/avatars/dragon-${still}.webp`;
}

/** The moods that move; "error" is a still, on the phone too. */
export type ClipMood = "idle" | "working" | "waiting" | "happy";

function clipMood(mood: string): ClipMood {
  return mood === "working" || mood === "waiting" || mood === "happy" ? mood : "idle";
}

/** The default Grok-bot avatar clips (disabled in favor of crisp stills). */
export function dragonClipUrl(_mood: string): string | null {
  return null;
}

/**
 * A studio face's clip for a mood, `avatar/<id>/<mood>.mp4` through the files API. The studio
 * makes them after the stills when its endpoint has a video model; a face may have none, in
 * which case the request fails and the still stays (see Avatar).
 */
export function studioClipUrl(id: string, mood: string): string | null {
  if (mood === "error") return null;
  return fileUrl(`avatar/${id}/${clipMood(mood)}.mp4`);
}
