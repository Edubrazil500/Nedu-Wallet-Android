import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

const FLUTTERWAVE_API = 'https://api.flutterwave.com/v3';

/**
 * Sandbox-only provider adapter.
 *
 * This repository also contains an independent NestJS/Prisma prototype. It is
 * not the production Supabase wallet backend. Keep this adapter unable to make
 * live-provider requests until a separate production release is reviewed.
 */
@Injectable()
export class FlutterwaveService {
  constructor(private config: ConfigService) {}

  async request<T>(path: string, method: 'GET' | 'POST', body?: unknown): Promise<T> {
    const secret = this.config.get<string>('FLW_SECRET_KEY')?.trim();
    const environment = this.config.get<string>('FLW_ENVIRONMENT')?.trim().toLowerCase();

    // A boolean deployment flag must never be enough to turn on real-money API
    // calls. Development and CI accept only a Flutterwave Test secret.
    if (environment !== 'test' || !secret?.startsWith('FLWSECK_TEST-')) {
      throw new ServiceUnavailableException(
        'Flutterwave sandbox is not configured. Live payment requests are disabled in this build.',
      );
    }

    if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\\\')) {
      throw new ServiceUnavailableException('Invalid Flutterwave API path.');
    }

    let response: Response;
    try {
      response = await fetch(FLUTTERWAVE_API + path, {
        method,
        headers: {
          Authorization: 'Bearer ' + secret,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(15000),
      });
    } catch {
      throw new ServiceUnavailableException(
        'Flutterwave sandbox is temporarily unavailable. Reconcile the transaction before retrying.',
      );
    }

    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new ServiceUnavailableException(
        'Flutterwave sandbox request failed. Reconcile the transaction before retrying.',
      );
    }
    return payload as T;
  }
}
