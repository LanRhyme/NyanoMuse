import { Loader2 } from "lucide-react";
import { useId, useState } from "react";
import { api } from "../api";
import { useT } from "../i18n";
import { cx } from "../util";
import { inputCls, primaryBtn } from "./Form";

export function SignIn({
  onSignedIn,
  useAsModel = false,
  cta,
  autoFocus = true,
}: {
  onSignedIn: () => void | Promise<void>;
  useAsModel?: boolean;
  cta?: string;
  autoFocus?: boolean;
}) {
  const t = useT();
  const ids = useId();
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState<"login" | "model" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const finish = async () => {
    if (useAsModel) {
      setBusy("model");
      try {
        await api.cloudUseAsModel();
      } catch {
        /* the account is in; the model can be picked in Connections */
      }
    }
    await onSignedIn();
  };

  const login = async () => {
    const id = identifier.trim();
    if (!id || !password) return;
    setBusy("login");
    setError(null);
    try {
      await api.cloudLogin(id, password);
      setPassword("");
      await finish();
    } catch (e) {
      setError(t((e as Error).message));
    } finally {
      setBusy(null);
    }
  };

  const label = cta ?? (useAsModel ? t("Sign in and use its model") : t("Sign in"));

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        void login();
      }}
    >
      <div>
        <label htmlFor={`${ids}-id`} className="text-[12px] text-muted">
          {t("Username or account")}
        </label>
        <input
          id={`${ids}-id`}
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          autoComplete="username"
          autoFocus={autoFocus}
          placeholder="admin / username"
          className={cx(inputCls, "mt-1")}
        />
      </div>
      <div>
        <label htmlFor={`${ids}-password`} className="text-[12px] text-muted">
          {t("Password")}
        </label>
        <input
          id={`${ids}-password`}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          placeholder="••••••••"
          className={cx(inputCls, "mt-1")}
        />
      </div>
      {error && (
        <div role="alert" className="rounded-2xl bg-rose-500/12 px-3 py-2 text-[12.5px] text-rose-700 dark:text-rose-300">
          {error}
        </div>
      )}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={busy !== null || !identifier.trim() || !password}
          className={cx(primaryBtn, "flex-1 py-3")}
        >
          {busy ? <Loader2 size={15} className="animate-spin" /> : null} {label}
        </button>
      </div>
    </form>
  );
}
