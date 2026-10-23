// No React imports: the root layout (a server component) inlines THEME_SCRIPT; lib/theme.ts holds the client hook.
export const THEME_KEY = "af:theme";

/** Inlined in the root layout's <head>: applies the saved theme before first paint, so nothing flashes. */
export const THEME_SCRIPT = `(function(){try{var p=localStorage.getItem("${THEME_KEY}");var d=p==="dark"||(p!=="light"&&matchMedia("(prefers-color-scheme: dark)").matches);document.documentElement.setAttribute("data-theme",d?"dark":"light")}catch(e){}})()`;
