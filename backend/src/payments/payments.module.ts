import { Module } from '@nestjs/common';
import { FlutterwaveService } from './flutterwave.service';
import { PaymentsController } from './payments.controller';
import { PaymentsService } from './payments.service';
import { LedgerService } from './ledger.service';
import { FlutterwaveWebhookController } from './webhook.controller';
@Module({ controllers: [PaymentsController, FlutterwaveWebhookController], providers: [FlutterwaveService, PaymentsService, LedgerService] })
export class PaymentsModule {}
