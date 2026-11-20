import { IsEmail, IsOptional, IsString, Matches, MinLength, MaxLength } from 'class-validator';
export class RegisterDto {
  @IsEmail() email: string;
  @IsString() @MinLength(10) @MaxLength(72) password: string;
  @IsOptional() @IsString() @MaxLength(40) firstName?: string;
  @IsOptional() @IsString() @MaxLength(40) lastName?: string;
  @IsOptional() @Matches(/^\\+?[0-9]{8,15}$/) phone?: string;
}
export class LoginDto {
  @IsEmail() email: string;
  @IsString() @MinLength(1) @MaxLength(72) password: string;
}
