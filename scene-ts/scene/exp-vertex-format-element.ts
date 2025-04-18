// automatically generated by the FlatBuffers compiler, do not modify

import * as flatbuffers from 'flatbuffers';

import { ExpVertexElementType } from '../scene/exp-vertex-element-type.js';
import { ExpVertexElementUsage } from '../scene/exp-vertex-element-usage.js';


export class ExpVertexFormatElement {
  bb: flatbuffers.ByteBuffer|null = null;
  bb_pos = 0;
  __init(i:number, bb:flatbuffers.ByteBuffer):ExpVertexFormatElement {
  this.bb_pos = i;
  this.bb = bb;
  return this;
}

index():number {
  return this.bb!.readUint8(this.bb_pos);
}

type():ExpVertexElementType {
  return this.bb!.readUint8(this.bb_pos + 1);
}

usage():ExpVertexElementUsage {
  return this.bb!.readUint8(this.bb_pos + 2);
}

count():number {
  return this.bb!.readUint8(this.bb_pos + 3);
}

offset():number {
  return this.bb!.readUint8(this.bb_pos + 4);
}

byteSize():number {
  return this.bb!.readUint8(this.bb_pos + 5);
}

normalized():boolean {
  return !!this.bb!.readInt8(this.bb_pos + 6);
}

static sizeOf():number {
  return 7;
}

static createExpVertexFormatElement(builder:flatbuffers.Builder, index: number, type: ExpVertexElementType, usage: ExpVertexElementUsage, count: number, offset: number, byte_size: number, normalized: boolean):flatbuffers.Offset {
  builder.prep(1, 7);
  builder.writeInt8(Number(Boolean(normalized)));
  builder.writeInt8(byte_size);
  builder.writeInt8(offset);
  builder.writeInt8(count);
  builder.writeInt8(usage);
  builder.writeInt8(type);
  builder.writeInt8(index);
  return builder.offset();
}

}
