// Code generated by protoc-gen-go. DO NOT EDIT.
// source: beam_provision_api.proto

package fnexecution_v1

import proto "github.com/golang/protobuf/proto"
import fmt "fmt"
import math "math"
import _struct "github.com/golang/protobuf/ptypes/struct"

import (
	context "golang.org/x/net/context"
	grpc "google.golang.org/grpc"
)

// Reference imports to suppress errors if they are not otherwise used.
var _ = proto.Marshal
var _ = fmt.Errorf
var _ = math.Inf

// This is a compile-time assertion to ensure that this generated file
// is compatible with the proto package it is being compiled against.
// A compilation error at this line likely means your copy of the
// proto package needs to be updated.
const _ = proto.ProtoPackageIsVersion2 // please upgrade the proto package

// A request to get the provision info of a SDK harness worker instance.
type GetProvisionInfoRequest struct {
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *GetProvisionInfoRequest) Reset()         { *m = GetProvisionInfoRequest{} }
func (m *GetProvisionInfoRequest) String() string { return proto.CompactTextString(m) }
func (*GetProvisionInfoRequest) ProtoMessage()    {}
func (*GetProvisionInfoRequest) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{0}
}
func (m *GetProvisionInfoRequest) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_GetProvisionInfoRequest.Unmarshal(m, b)
}
func (m *GetProvisionInfoRequest) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_GetProvisionInfoRequest.Marshal(b, m, deterministic)
}
func (dst *GetProvisionInfoRequest) XXX_Merge(src proto.Message) {
	xxx_messageInfo_GetProvisionInfoRequest.Merge(dst, src)
}
func (m *GetProvisionInfoRequest) XXX_Size() int {
	return xxx_messageInfo_GetProvisionInfoRequest.Size(m)
}
func (m *GetProvisionInfoRequest) XXX_DiscardUnknown() {
	xxx_messageInfo_GetProvisionInfoRequest.DiscardUnknown(m)
}

var xxx_messageInfo_GetProvisionInfoRequest proto.InternalMessageInfo

// A response containing the provision info of a SDK harness worker instance.
type GetProvisionInfoResponse struct {
	Info                 *ProvisionInfo `protobuf:"bytes,1,opt,name=info" json:"info,omitempty"`
	XXX_NoUnkeyedLiteral struct{}       `json:"-"`
	XXX_unrecognized     []byte         `json:"-"`
	XXX_sizecache        int32          `json:"-"`
}

func (m *GetProvisionInfoResponse) Reset()         { *m = GetProvisionInfoResponse{} }
func (m *GetProvisionInfoResponse) String() string { return proto.CompactTextString(m) }
func (*GetProvisionInfoResponse) ProtoMessage()    {}
func (*GetProvisionInfoResponse) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{1}
}
func (m *GetProvisionInfoResponse) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_GetProvisionInfoResponse.Unmarshal(m, b)
}
func (m *GetProvisionInfoResponse) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_GetProvisionInfoResponse.Marshal(b, m, deterministic)
}
func (dst *GetProvisionInfoResponse) XXX_Merge(src proto.Message) {
	xxx_messageInfo_GetProvisionInfoResponse.Merge(dst, src)
}
func (m *GetProvisionInfoResponse) XXX_Size() int {
	return xxx_messageInfo_GetProvisionInfoResponse.Size(m)
}
func (m *GetProvisionInfoResponse) XXX_DiscardUnknown() {
	xxx_messageInfo_GetProvisionInfoResponse.DiscardUnknown(m)
}

var xxx_messageInfo_GetProvisionInfoResponse proto.InternalMessageInfo

func (m *GetProvisionInfoResponse) GetInfo() *ProvisionInfo {
	if m != nil {
		return m.Info
	}
	return nil
}

// Runtime provisioning information for a SDK harness worker instance,
// such as pipeline options, resource constraints and other job metadata
type ProvisionInfo struct {
	// (required) The job ID.
	JobId string `protobuf:"bytes,1,opt,name=job_id,json=jobId" json:"job_id,omitempty"`
	// (required) The job name.
	JobName string `protobuf:"bytes,2,opt,name=job_name,json=jobName" json:"job_name,omitempty"`
	// (required) The worker ID.
	WorkerId string `protobuf:"bytes,5,opt,name=worker_id,json=workerId" json:"worker_id,omitempty"`
	// (required) Pipeline options. For non-template jobs, the options are
	// identical to what is passed to job submission.
	PipelineOptions *_struct.Struct `protobuf:"bytes,3,opt,name=pipeline_options,json=pipelineOptions" json:"pipeline_options,omitempty"`
	// (optional) Resource limits that the SDK harness worker should respect.
	// Runners may -- but are not required to -- enforce any limits provided.
	ResourceLimits       *Resources `protobuf:"bytes,4,opt,name=resource_limits,json=resourceLimits" json:"resource_limits,omitempty"`
	XXX_NoUnkeyedLiteral struct{}   `json:"-"`
	XXX_unrecognized     []byte     `json:"-"`
	XXX_sizecache        int32      `json:"-"`
}

func (m *ProvisionInfo) Reset()         { *m = ProvisionInfo{} }
func (m *ProvisionInfo) String() string { return proto.CompactTextString(m) }
func (*ProvisionInfo) ProtoMessage()    {}
func (*ProvisionInfo) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{2}
}
func (m *ProvisionInfo) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_ProvisionInfo.Unmarshal(m, b)
}
func (m *ProvisionInfo) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_ProvisionInfo.Marshal(b, m, deterministic)
}
func (dst *ProvisionInfo) XXX_Merge(src proto.Message) {
	xxx_messageInfo_ProvisionInfo.Merge(dst, src)
}
func (m *ProvisionInfo) XXX_Size() int {
	return xxx_messageInfo_ProvisionInfo.Size(m)
}
func (m *ProvisionInfo) XXX_DiscardUnknown() {
	xxx_messageInfo_ProvisionInfo.DiscardUnknown(m)
}

var xxx_messageInfo_ProvisionInfo proto.InternalMessageInfo

func (m *ProvisionInfo) GetJobId() string {
	if m != nil {
		return m.JobId
	}
	return ""
}

func (m *ProvisionInfo) GetJobName() string {
	if m != nil {
		return m.JobName
	}
	return ""
}

func (m *ProvisionInfo) GetWorkerId() string {
	if m != nil {
		return m.WorkerId
	}
	return ""
}

func (m *ProvisionInfo) GetPipelineOptions() *_struct.Struct {
	if m != nil {
		return m.PipelineOptions
	}
	return nil
}

func (m *ProvisionInfo) GetResourceLimits() *Resources {
	if m != nil {
		return m.ResourceLimits
	}
	return nil
}

// Resources specify limits for local resources, such memory and cpu. It
// is used to inform SDK harnesses of their allocated footprint.
type Resources struct {
	// (optional) Memory usage limits. SDKs can use this value to configure
	// internal buffer sizes and language specific sizes.
	Memory *Resources_Memory `protobuf:"bytes,1,opt,name=memory" json:"memory,omitempty"`
	// (optional) CPU usage limits.
	Cpu *Resources_Cpu `protobuf:"bytes,2,opt,name=cpu" json:"cpu,omitempty"`
	// (optional) Disk size limits for the semi-persistent location.
	SemiPersistentDisk   *Resources_Disk `protobuf:"bytes,3,opt,name=semi_persistent_disk,json=semiPersistentDisk" json:"semi_persistent_disk,omitempty"`
	XXX_NoUnkeyedLiteral struct{}        `json:"-"`
	XXX_unrecognized     []byte          `json:"-"`
	XXX_sizecache        int32           `json:"-"`
}

func (m *Resources) Reset()         { *m = Resources{} }
func (m *Resources) String() string { return proto.CompactTextString(m) }
func (*Resources) ProtoMessage()    {}
func (*Resources) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{3}
}
func (m *Resources) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_Resources.Unmarshal(m, b)
}
func (m *Resources) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_Resources.Marshal(b, m, deterministic)
}
func (dst *Resources) XXX_Merge(src proto.Message) {
	xxx_messageInfo_Resources.Merge(dst, src)
}
func (m *Resources) XXX_Size() int {
	return xxx_messageInfo_Resources.Size(m)
}
func (m *Resources) XXX_DiscardUnknown() {
	xxx_messageInfo_Resources.DiscardUnknown(m)
}

var xxx_messageInfo_Resources proto.InternalMessageInfo

func (m *Resources) GetMemory() *Resources_Memory {
	if m != nil {
		return m.Memory
	}
	return nil
}

func (m *Resources) GetCpu() *Resources_Cpu {
	if m != nil {
		return m.Cpu
	}
	return nil
}

func (m *Resources) GetSemiPersistentDisk() *Resources_Disk {
	if m != nil {
		return m.SemiPersistentDisk
	}
	return nil
}

// Memory limits.
type Resources_Memory struct {
	// (optional) Hard limit in bytes. A zero value means unspecified.
	Size                 uint64   `protobuf:"varint,1,opt,name=size" json:"size,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *Resources_Memory) Reset()         { *m = Resources_Memory{} }
func (m *Resources_Memory) String() string { return proto.CompactTextString(m) }
func (*Resources_Memory) ProtoMessage()    {}
func (*Resources_Memory) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{3, 0}
}
func (m *Resources_Memory) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_Resources_Memory.Unmarshal(m, b)
}
func (m *Resources_Memory) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_Resources_Memory.Marshal(b, m, deterministic)
}
func (dst *Resources_Memory) XXX_Merge(src proto.Message) {
	xxx_messageInfo_Resources_Memory.Merge(dst, src)
}
func (m *Resources_Memory) XXX_Size() int {
	return xxx_messageInfo_Resources_Memory.Size(m)
}
func (m *Resources_Memory) XXX_DiscardUnknown() {
	xxx_messageInfo_Resources_Memory.DiscardUnknown(m)
}

var xxx_messageInfo_Resources_Memory proto.InternalMessageInfo

func (m *Resources_Memory) GetSize() uint64 {
	if m != nil {
		return m.Size
	}
	return 0
}

// CPU limits.
type Resources_Cpu struct {
	// (optional) Shares of a cpu to use. Fractional values, such as "0.2"
	// or "2.5", are fine. Any value <= 0 means unspecified.
	Shares               float32  `protobuf:"fixed32,1,opt,name=shares" json:"shares,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *Resources_Cpu) Reset()         { *m = Resources_Cpu{} }
func (m *Resources_Cpu) String() string { return proto.CompactTextString(m) }
func (*Resources_Cpu) ProtoMessage()    {}
func (*Resources_Cpu) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{3, 1}
}
func (m *Resources_Cpu) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_Resources_Cpu.Unmarshal(m, b)
}
func (m *Resources_Cpu) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_Resources_Cpu.Marshal(b, m, deterministic)
}
func (dst *Resources_Cpu) XXX_Merge(src proto.Message) {
	xxx_messageInfo_Resources_Cpu.Merge(dst, src)
}
func (m *Resources_Cpu) XXX_Size() int {
	return xxx_messageInfo_Resources_Cpu.Size(m)
}
func (m *Resources_Cpu) XXX_DiscardUnknown() {
	xxx_messageInfo_Resources_Cpu.DiscardUnknown(m)
}

var xxx_messageInfo_Resources_Cpu proto.InternalMessageInfo

func (m *Resources_Cpu) GetShares() float32 {
	if m != nil {
		return m.Shares
	}
	return 0
}

// Disk limits.
type Resources_Disk struct {
	// (optional) Hard limit in bytes. A zero value means unspecified.
	Size                 uint64   `protobuf:"varint,1,opt,name=size" json:"size,omitempty"`
	XXX_NoUnkeyedLiteral struct{} `json:"-"`
	XXX_unrecognized     []byte   `json:"-"`
	XXX_sizecache        int32    `json:"-"`
}

func (m *Resources_Disk) Reset()         { *m = Resources_Disk{} }
func (m *Resources_Disk) String() string { return proto.CompactTextString(m) }
func (*Resources_Disk) ProtoMessage()    {}
func (*Resources_Disk) Descriptor() ([]byte, []int) {
	return fileDescriptor_beam_provision_api_cbabe88783201f79, []int{3, 2}
}
func (m *Resources_Disk) XXX_Unmarshal(b []byte) error {
	return xxx_messageInfo_Resources_Disk.Unmarshal(m, b)
}
func (m *Resources_Disk) XXX_Marshal(b []byte, deterministic bool) ([]byte, error) {
	return xxx_messageInfo_Resources_Disk.Marshal(b, m, deterministic)
}
func (dst *Resources_Disk) XXX_Merge(src proto.Message) {
	xxx_messageInfo_Resources_Disk.Merge(dst, src)
}
func (m *Resources_Disk) XXX_Size() int {
	return xxx_messageInfo_Resources_Disk.Size(m)
}
func (m *Resources_Disk) XXX_DiscardUnknown() {
	xxx_messageInfo_Resources_Disk.DiscardUnknown(m)
}

var xxx_messageInfo_Resources_Disk proto.InternalMessageInfo

func (m *Resources_Disk) GetSize() uint64 {
	if m != nil {
		return m.Size
	}
	return 0
}

func init() {
	proto.RegisterType((*GetProvisionInfoRequest)(nil), "org.apache.beam.model.fn_execution.v1.GetProvisionInfoRequest")
	proto.RegisterType((*GetProvisionInfoResponse)(nil), "org.apache.beam.model.fn_execution.v1.GetProvisionInfoResponse")
	proto.RegisterType((*ProvisionInfo)(nil), "org.apache.beam.model.fn_execution.v1.ProvisionInfo")
	proto.RegisterType((*Resources)(nil), "org.apache.beam.model.fn_execution.v1.Resources")
	proto.RegisterType((*Resources_Memory)(nil), "org.apache.beam.model.fn_execution.v1.Resources.Memory")
	proto.RegisterType((*Resources_Cpu)(nil), "org.apache.beam.model.fn_execution.v1.Resources.Cpu")
	proto.RegisterType((*Resources_Disk)(nil), "org.apache.beam.model.fn_execution.v1.Resources.Disk")
}

// Reference imports to suppress errors if they are not otherwise used.
var _ context.Context
var _ grpc.ClientConn

// This is a compile-time assertion to ensure that this generated file
// is compatible with the grpc package it is being compiled against.
const _ = grpc.SupportPackageIsVersion4

// ProvisionServiceClient is the client API for ProvisionService service.
//
// For semantics around ctx use and closing/ending streaming RPCs, please refer to https://godoc.org/google.golang.org/grpc#ClientConn.NewStream.
type ProvisionServiceClient interface {
	// Get provision information for the SDK harness worker instance.
	GetProvisionInfo(ctx context.Context, in *GetProvisionInfoRequest, opts ...grpc.CallOption) (*GetProvisionInfoResponse, error)
}

type provisionServiceClient struct {
	cc *grpc.ClientConn
}

func NewProvisionServiceClient(cc *grpc.ClientConn) ProvisionServiceClient {
	return &provisionServiceClient{cc}
}

func (c *provisionServiceClient) GetProvisionInfo(ctx context.Context, in *GetProvisionInfoRequest, opts ...grpc.CallOption) (*GetProvisionInfoResponse, error) {
	out := new(GetProvisionInfoResponse)
	err := c.cc.Invoke(ctx, "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

// Server API for ProvisionService service

type ProvisionServiceServer interface {
	// Get provision information for the SDK harness worker instance.
	GetProvisionInfo(context.Context, *GetProvisionInfoRequest) (*GetProvisionInfoResponse, error)
}

func RegisterProvisionServiceServer(s *grpc.Server, srv ProvisionServiceServer) {
	s.RegisterService(&_ProvisionService_serviceDesc, srv)
}

func _ProvisionService_GetProvisionInfo_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetProvisionInfoRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(ProvisionServiceServer).GetProvisionInfo(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/org.apache.beam.model.fn_execution.v1.ProvisionService/GetProvisionInfo",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(ProvisionServiceServer).GetProvisionInfo(ctx, req.(*GetProvisionInfoRequest))
	}
	return interceptor(ctx, in, info, handler)
}

var _ProvisionService_serviceDesc = grpc.ServiceDesc{
	ServiceName: "org.apache.beam.model.fn_execution.v1.ProvisionService",
	HandlerType: (*ProvisionServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "GetProvisionInfo",
			Handler:    _ProvisionService_GetProvisionInfo_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "beam_provision_api.proto",
}

func init() {
	proto.RegisterFile("beam_provision_api.proto", fileDescriptor_beam_provision_api_cbabe88783201f79)
}

var fileDescriptor_beam_provision_api_cbabe88783201f79 = []byte{
	// 485 bytes of a gzipped FileDescriptorProto
	0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xff, 0x9c, 0x93, 0xdd, 0x6e, 0xd3, 0x40,
	0x10, 0x85, 0x95, 0xc4, 0x35, 0xcd, 0x00, 0x6d, 0xb4, 0x02, 0xea, 0x9a, 0x22, 0xa1, 0x08, 0x24,
	0xae, 0xb6, 0xb4, 0x80, 0xb8, 0x03, 0x91, 0x56, 0x40, 0x24, 0xa0, 0xd5, 0xf6, 0x0a, 0x6e, 0x2c,
	0xff, 0x4c, 0xd2, 0x4d, 0x62, 0xcf, 0xb2, 0xbb, 0x0e, 0x3f, 0xaf, 0xc1, 0x4b, 0xf0, 0x64, 0xbc,
	0x05, 0x12, 0xf2, 0xda, 0x09, 0x2d, 0x50, 0x29, 0xe5, 0xce, 0x9e, 0xb3, 0xe7, 0xd3, 0xcc, 0xd9,
	0x59, 0x08, 0x12, 0x8c, 0xf3, 0x48, 0x69, 0x9a, 0x4b, 0x23, 0xa9, 0x88, 0x62, 0x25, 0xb9, 0xd2,
	0x64, 0x89, 0xdd, 0x27, 0x3d, 0xe6, 0xb1, 0x8a, 0xd3, 0x53, 0xe4, 0xd5, 0x21, 0x9e, 0x53, 0x86,
	0x33, 0x3e, 0x2a, 0x22, 0xfc, 0x8c, 0x69, 0x69, 0x25, 0x15, 0x7c, 0xbe, 0x17, 0xee, 0x8c, 0x89,
	0xc6, 0x33, 0xdc, 0x75, 0xa6, 0xa4, 0x1c, 0xed, 0x1a, 0xab, 0xcb, 0xd4, 0xd6, 0x90, 0xfe, 0x36,
	0x6c, 0xbd, 0x42, 0x7b, 0xbc, 0xc0, 0x0f, 0x8b, 0x11, 0x09, 0xfc, 0x58, 0xa2, 0xb1, 0xfd, 0x0c,
	0x82, 0xbf, 0x25, 0xa3, 0xa8, 0x30, 0xc8, 0x5e, 0x83, 0x27, 0x8b, 0x11, 0x05, 0xad, 0xbb, 0xad,
	0x07, 0x57, 0xf7, 0x1f, 0xf3, 0x95, 0x5a, 0xe1, 0xe7, 0x59, 0x8e, 0xd0, 0xff, 0xd9, 0x82, 0xeb,
	0xe7, 0xea, 0xec, 0x26, 0xf8, 0x13, 0x4a, 0x22, 0x99, 0x39, 0x7a, 0x57, 0xac, 0x4d, 0x28, 0x19,
	0x66, 0x6c, 0x1b, 0xd6, 0xab, 0x72, 0x11, 0xe7, 0x18, 0xb4, 0x9d, 0x70, 0x65, 0x42, 0xc9, 0xbb,
	0x38, 0x47, 0x76, 0x1b, 0xba, 0x9f, 0x48, 0x4f, 0x51, 0x57, 0xa6, 0x35, 0xa7, 0xad, 0xd7, 0x85,
	0x61, 0xc6, 0x06, 0xd0, 0x53, 0x52, 0xe1, 0x4c, 0x16, 0x18, 0x91, 0xaa, 0x5a, 0x31, 0x41, 0xc7,
	0xb5, 0xbd, 0xc5, 0xeb, 0x68, 0xf8, 0x22, 0x1a, 0x7e, 0xe2, 0xa2, 0x11, 0x9b, 0x0b, 0xc3, 0x51,
	0x7d, 0x9e, 0xbd, 0x87, 0x4d, 0x8d, 0x86, 0x4a, 0x9d, 0x62, 0x34, 0x93, 0xb9, 0xb4, 0x26, 0xf0,
	0x1c, 0xe2, 0xe1, 0x8a, 0x93, 0x8b, 0xc6, 0x6d, 0xc4, 0xc6, 0x02, 0xf4, 0xc6, 0x71, 0xfa, 0x3f,
	0xda, 0xd0, 0x5d, 0xaa, 0xec, 0x08, 0xfc, 0x1c, 0x73, 0xd2, 0x5f, 0x9a, 0x64, 0x9f, 0x5e, 0x96,
	0xcf, 0xdf, 0x3a, 0xbb, 0x68, 0x30, 0xec, 0x25, 0x74, 0x52, 0x55, 0xba, 0xc0, 0x56, 0xbf, 0xa7,
	0xdf, 0xb4, 0x03, 0x55, 0x8a, 0x0a, 0xc0, 0xc6, 0x70, 0xc3, 0x60, 0x2e, 0x23, 0x85, 0xda, 0x48,
	0x63, 0xb1, 0xb0, 0x51, 0x26, 0xcd, 0xb4, 0x49, 0xf2, 0xc9, 0xa5, 0xc1, 0x87, 0xd2, 0x4c, 0x05,
	0xab, 0x90, 0xc7, 0x4b, 0x62, 0x55, 0x0b, 0x77, 0xc0, 0xaf, 0x47, 0x60, 0x0c, 0x3c, 0x23, 0xbf,
	0xa2, 0x4b, 0xc2, 0x13, 0xee, 0x3b, 0xbc, 0x03, 0x9d, 0x03, 0x55, 0xb2, 0x5b, 0xe0, 0x9b, 0xd3,
	0x58, 0xa3, 0x71, 0x62, 0x5b, 0x34, 0x7f, 0x61, 0x08, 0x5e, 0x05, 0xf9, 0x97, 0x75, 0xff, 0x7b,
	0x0b, 0x7a, 0xcb, 0x45, 0x3b, 0x41, 0x3d, 0x97, 0x29, 0xb2, 0x6f, 0x2d, 0xe8, 0xfd, 0xb9, 0xe4,
	0xec, 0xd9, 0x8a, 0xd3, 0x5c, 0xf0, 0x70, 0xc2, 0xe7, 0xff, 0xed, 0xaf, 0x5f, 0xd7, 0xe0, 0x10,
	0xee, 0x5d, 0x44, 0x38, 0x0b, 0x18, 0x5c, 0x5b, 0xda, 0x5f, 0x28, 0xf9, 0x61, 0xe3, 0x8c, 0x1a,
	0xcd, 0xf7, 0x12, 0xdf, 0xad, 0xf5, 0xa3, 0x5f, 0x01, 0x00, 0x00, 0xff, 0xff, 0x6d, 0x4f, 0xe3,
	0xdd, 0x42, 0x04, 0x00, 0x00,
}
