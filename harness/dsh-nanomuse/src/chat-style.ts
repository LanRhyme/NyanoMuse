/**
 * How the agent writes in the chat, on the desktop: the three tags the phone taught the
 * model, in one prompt context.
 *
 * - `<nm:bubble/>` splits one reply into the two or three short bubbles a person would
 *   send, which the browser half draws as separate cards.
 * - `<nm:mood feel="…" reason="…"/>` says how the reply felt; the mood service reads it
 *   and keeps it off the screen.
 * - `<nm:meme id="…"/>` sends one of the person's stickers.
 *
 * The paragraphs are volatile (they carry the live mood and the sticker catalogue), so
 * this is a preset row rather than part of the persona: the persona is stable text, and a
 * section that changes with the conversation would poison the prompt cache for everything
 * after it.
 */
import type { Context } from '@deepseek-ai/cordis'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type NanomuseMood from './mood.ts'
import type NanomuseStickers from './stickers.ts'

export const name = 'nanomuse-chat-style'

/** The mood service is the one this row cannot do without; stickers are optional. */
export const inject = ['systemPrompt', 'nanomuseMood']

/** When a reply becomes two or three bubbles, and when it must not. */
export const BUBBLE_RULE = [
  '## How a reply is shaped in the chat (nanoMuse)',
  'In an ordinary exchange — a greeting, a joke, small talk, a short question — write the way a person texts: two or three short bubbles in a row, one or two sentences each, with `<nm:bubble/>` where one bubble ends and the next begins. At most two separators per reply.',
  'Example: 哈哈确实！<nm:bubble/>我刚才还在琢磨这事呢，你怎么突然想起来了？',
  'Never split a reply that is code, a long technical explanation, a Markdown table, or a turn that calls tools: those stay one continuous bubble.',
].join('\n')

export function apply(ctx: Context): void {
  ctx.inject(['systemPrompt'], (ctx) => {
    ctx.effect(
      () =>
        ctx.systemPrompt.context({
          name: 'nanomuse-chat-style',
          order: 57,
          text: () => {
            const mood = ctx.get('nanomuseMood') as NanomuseMood | undefined
            const stickers = ctx.get('nanomuseStickers') as NanomuseStickers | undefined
            const parts = [BUBBLE_RULE]
            const memes = stickers?.paragraph()
            if (memes) parts.push(memes)
            if (mood) parts.push(mood.prompt())
            return parts.join('\n\n')
          },
        }),
      'nanomuse chat style: context',
    )
  })
}
