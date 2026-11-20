import { Controller, Headers, HttpCode, Post, RawBodyRequest, Req, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { createHmac, timingSafeEqual } from 'crypto';
import { Request } from 'express';
import { PrismaService } from '../prisma/prisma.module';
import { FlutterwaveService } from './flutterwave.service';
import { LedgerService } from './ledger.service';
@Controller('webhooks')
export class FlutterwaveWebhookController {
  constructor(private config: ConfigService, private prisma: PrismaService, private flutterwave: FlutterwaveService, private ledger: LedgerService) {}
  @Post('flutterwave') @HttpCode(200)
  async handle(@Req() req: RawBodyRequest<Request>, @Headers('verif-hash') signature?: string) {
    const secret = this.config.get<string>('FLW_WEBHOOK_SECRET');
    if (!secret || !signature) throw new UnauthorizedException();
    const expected = Buffer.from(createHmac('sha256', secret).update(req.rawBody ?? Buffer.alloc(0)).digest('hex'));
    const received = Buffer.from(signature);
    if (expected.length !== received.length || !timingSafeEqual(expected, received)) throw new UnauthorizedException();
    const body: any = req.body ?? {}, data = body.data ?? {};
    const eventKey = String(data.id ?? data.flw_ref ?? data.tx_ref ?? '');
    if (!eventKey) return { received: true, ignored: true };
    const eventType = String(body.event ?? body['event.type'] ?? 'unknown');
    const event = await this.prisma.webhookEvent.upsert({ where: { eventKey }, create: { eventKey, eventType, payload: body }, update: {} });
    if (event.processedAt) return { received: true, duplicate: true };
    if (data.id && data.tx_ref && eventType.toLowerCase().includes('charge')) {
      const verified: any = await this.flutterwave.request('/transactions/' + encodeURIComponent(String(data.id)) + '/verify', 'GET');
      const v = verified?.data;
      const payment = await this.prisma.paymentTransaction.findUnique({ where: { txRef: String(data.tx_ref) } });
      if (payment && v?.status === 'successful' && v.tx_ref === payment.txRef && v.currency === payment.currency && Number(v.amount) >= Number(payment.amount)) {
        await this.ledger.creditWalletFromVerifiedDeposit(payment.userId, payment.txRef, payment.amount.toString(), payment.currency);
      }
    }
    await this.prisma.webhookEvent.update({ where: { eventKey }, data: { processedAt: new Date() } });
    return { received: true };
  }
}
