import { Check, Sparkles } from "lucide-react";
import { DRAGON, dragonUrl, isDragon, studioUrl } from "../avatars";
import { useT } from "../i18n";
import { cx } from "../util";

const EMOJI = ["✨", "🌙", "🪐", "🌿", "🔥", "🌊", "🦉", "🦊", "🐙", "🎯", "🧭", "💎", "🍀", "🎈", "🤖", "🧠"];
export const AVATAR_COLORS = ["#0064d4", "#7c3aed", "#0891b2", "#059669", "#d97706", "#dc2626", "#db2777", "#4b5563"];

export interface AvatarChoice {
  avatar: string;
  emoji: string;
  color: string;
}

/** The looks a face can be drawn in — the phone's list, same ids; the runtime knows the words. */
export const AVATAR_STYLES: { id: string; label: string }[] = [
  { id: "muse", label: "3D toy (Muse)" },
  { id: "flat", label: "Flat" },
  { id: "clay", label: "3D clay" },
  { id: "watercolor", label: "Watercolour" },
  { id: "pixel", label: "Pixel" },
  { id: "line", label: "Line" },
  { id: "sticker", label: "Sticker" },
];

/**
 * The dragon, a face drawn for you in the avatar studio (when the profile wears one), a tile
 * that opens the studio (when `onStudio` is given — Settings; setup has no model yet), plus
 * an emoji on a colour for anyone who would rather.
 */
export function AvatarPicker({
  value,
  onChange,
  onStudio,
}: {
  value: AvatarChoice;
  onChange: (v: AvatarChoice) => void;
  /** the "draw a new one" tile: opens the avatar studio screen */
  onStudio?: () => void;
}) {
  const t = useT();
  const emojiMode = value.avatar === "";
  // a face from the studio: anything that is neither the dragon nor the emoji
  const studio = !emojiMode && !isDragon(value.avatar) ? value.avatar : null;
  const ring = "ring-[2.5px] ring-accent ring-offset-2 ring-offset-bg";
  const tile = "relative h-[72px] w-[72px] shrink-0 overflow-hidden rounded-full transition";
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-3">
        <button
          type="button"
          aria-label={t("The dragon")}
          aria-pressed={value.avatar === DRAGON}
          onClick={() => onChange({ ...value, avatar: DRAGON })}
          className={cx(tile, "bg-[#12151a]", value.avatar === DRAGON ? ring : "opacity-90 hover:opacity-100")}
        >
          <img src={dragonUrl(value.avatar === DRAGON ? "happy" : "idle")} alt="" draggable={false} className="h-full w-full object-cover" />
        </button>
        {studio && (
          <button type="button" aria-label={t("The face drawn for you")} aria-pressed className={cx(tile, "bg-surface-2", ring)}>
            <img src={studioUrl(studio, "happy")} alt="" draggable={false} className="h-full w-full object-cover" />
          </button>
        )}
        {onStudio && (
          <button
            type="button"
            aria-label={t("Avatar studio")}
            onClick={onStudio}
            className={cx(tile, "flex items-center justify-center border border-dashed border-border text-accent hover:bg-surface-2")}
          >
            <Sparkles size={22} />
          </button>
        )}
        <button
          type="button"
          aria-label={t("An emoji instead")}
          aria-pressed={emojiMode}
          onClick={() => onChange({ ...value, avatar: "" })}
          className={cx("flex h-[72px] w-[72px] shrink-0 items-center justify-center rounded-full text-[26px] transition", emojiMode ? ring : "opacity-90 hover:opacity-100")}
          style={{ background: `linear-gradient(135deg, ${value.color}, color-mix(in srgb, ${value.color} 60%, #ffffff))` }}
        >
          {value.emoji}
        </button>
      </div>
      {emojiMode && (
        <>
          <div className="grid grid-cols-8 gap-1.5">
            {EMOJI.map((e) => (
              <button
                key={e}
                type="button"
                onClick={() => onChange({ ...value, emoji: e })}
                className={cx("flex aspect-square items-center justify-center rounded-2xl bg-surface-2 text-[22px]", value.emoji === e && "ring-2 ring-accent")}
              >
                {e}
              </button>
            ))}
          </div>
          <div className="flex gap-2">
            {AVATAR_COLORS.map((c) => (
              <button
                key={c}
                type="button"
                aria-label={c}
                onClick={() => onChange({ ...value, color: c })}
                className="flex h-8 w-8 items-center justify-center rounded-full text-white"
                style={{ background: c }}
              >
                {value.color === c && <Check size={16} />}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
