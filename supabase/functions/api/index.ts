import { handleRequest } from '../_shared/ai.mjs';
Deno.serve((request: Request) => handleRequest(request, { env: Deno.env.toObject() }));
