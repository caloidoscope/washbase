// Vitest stand-in for the `server-only` package (aliased in vitest.config.mts). The real package throws when it is
// imported outside a React Server Components build, which a Node test run is not. Tests run on the server anyway.
export {};
