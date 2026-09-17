import { workerRequest } from '../_shared/research.mjs';
Deno.serve((request: Request) => workerRequest(request, { env: Deno.env.toObject() }));
