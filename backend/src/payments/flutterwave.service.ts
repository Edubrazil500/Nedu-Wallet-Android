import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
@Injectable()
export class FlutterwaveService {
  constructor(private config: ConfigService) {}
  async request<T>(path: string, method: 'GET' | 'POST', body?: unknown): Promise<T> {
    const secret = this.config.get<string>('FLW_SECRET_KEY');
    if (!secret) throw new ServiceUnavailableException('Flutterwave credentials are not configured.');
    if (this.config.get<string>('FLW_LIVE_ENABLED') !== 'true') throw new ServiceUnavailableException('Payment processing is disabled until production controls are approved.');
    const response = await fetch(this.config.get<string>('FLW_BASE_URL', 'https://api.flutterwave.com/v3') + path, {
      method, headers: { Authorization: 'Bearer ' + secret, 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000),
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new ServiceUnavailableException('Flutterwave request failed. Check server logs and reconcile before retrying.');
    return payload as T;
  }
}
